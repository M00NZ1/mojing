import html
import shutil
import tempfile
import time
from pathlib import Path
from urllib.parse import quote
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, Response, StreamingResponse
from starlette.background import BackgroundTask
from sqlalchemy import func, select
from sqlalchemy.orm import Session, joinedload, selectinload

from ..services.message_deletion_service import begin_storyline_write, deletion_impact, remove_unreferenced_message
from ..services.memory_source_service import memory_invalidation_plan, invalidate_source_memory
from ..database import get_db
from ..config import STORAGE_DIR
from ..models import (
    CharacterModel,
    ChatSessionModel,
    MessageBookmarkModel,
    MessageModel,
    SessionBranchModel,
    SessionCharacterStateModel,
    SessionMemoryCorrectionModel,
    SessionParticipantModel,
    SessionWorldModel,
    WorldEncyclopediaModel,
    WorldTemplateModel,
    now_utc,
)
from ..schemas import (
    GenerateRequest,
    MessageRead,
    MessageContextUpdate,
    MessageSearchHitRead,
    MessageSearchPageRead,
    MessagePage,
    MessageWindowPage,
    ParticipantRead,
    SpeakerPlanRead,
    SessionBranchCreate,
    SessionBranchRead,
    SessionCharacterStateRead,
    SessionCreate,
    SessionMessageCreate,
    SessionMessageEdit,
    SessionMemoryCorrectionRead,
    SessionMemoryCorrectionWrite,
    SessionParticipantCreate,
    SessionParticipantUpdate,
    SessionRead,
    SessionUpdate,
    SessionWorldRead,
    SessionWorldUpdate,
)
from ..services.chat_service import (
    BranchContextError,
    create_message,
    get_visible_message,
    get_visible_tail_message_id,
    get_session_messages_page,
    get_session_messages_after_page,
    get_session_message_window,
    list_visible_messages,
    last_message_previews_by_session_ids,
    list_sessions_with_counts,
    search_session_messages,
    resolve_branch_context,
    serialize_message,
    select_speakers_for_turn,
    sse_event,
    stream_character_reply,
    stream_narrator_reply,
    trigger_memory_compaction_async)
from ..services.backup_service import BackupError, build_portable_project_backup, validate_portable_project_backup
from ..services.export_service import build_session_export_archive
from ..services.session_exchange_service import SessionExchangeError, import_session_archive
from ..services.tavern_chat_import_service import parse_tavern_chat_file
from ..services.memory_service import ensure_session_character_state
from ..services.system_config_service import get_local_config, set_setting
from ..services.model_platform_service import (
    ModelChoiceWrite, ModelSelection, get_model_choice, resolve_selection,
)


router = APIRouter(prefix="/sessions", tags=["会话"])
BACKUP_ACTION_HEADER = "portable-backup-v1"
CHAT_UPLOAD_CHUNK_BYTES = 1024 * 1024


def _raise_branch_http_error(exc: BranchContextError) -> None:
    raise HTTPException(status_code=400, detail=str(exc)) from exc


def _chat_upload_limit(db: Session) -> tuple[int, int]:
    raw_value = get_local_config(db).get("max_upload_mb", 20)
    try:
        limit_mb = max(1, int(raw_value))
    except (TypeError, ValueError):
        limit_mb = 20
    return limit_mb, limit_mb * 1024 * 1024


def _write_chat_upload(file: UploadFile, upload_dir: Path, max_bytes: int, limit_mb: int) -> Path:
    suffix = Path(file.filename or "attachment.bin").suffix
    target = upload_dir / f"{uuid4().hex}{suffix}"
    written = 0
    try:
        with target.open("xb") as output:
            while chunk := file.file.read(CHAT_UPLOAD_CHUNK_BYTES):
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(
                        status_code=413,
                        detail=f"{file.filename or '所选文件'} 超过单个文件上传上限（{limit_mb} MB）",
                    )
                output.write(chunk)
    except Exception:
        target.unlink(missing_ok=True)
        raise
    return target


@router.get("", summary="获取会话列表", response_model=list[SessionRead])
def list_sessions(q: str = "", db: Session = Depends(get_db)):
    return list_sessions_with_counts(db, q)


@router.post("", summary="创建新会话", response_model=SessionRead)
def create_session(payload: SessionCreate, db: Session = Depends(get_db)):
    character_ids = list(dict.fromkeys(payload.initial_character_ids or []))
    if character_ids:
        existing_ids = set(db.scalars(select(CharacterModel.id).where(CharacterModel.id.in_(character_ids))))
        if any(cid not in existing_ids for cid in character_ids):
            raise HTTPException(status_code=409, detail="部分参与角色已不存在，请刷新角色列表后重新选择")
    runtime_config = get_local_config(db)
    final_template_id = payload.template_id or str(runtime_config.get("default_world_template_id") or "custom")
    def opening_flag(name: str) -> bool:
        if name in payload.model_fields_set:
            return bool(getattr(payload, name))
        return bool(runtime_config.get(f"default_{name}", getattr(payload, name)))

    final_narrator_enabled = opening_flag("narrator_enabled")
    final_choice_generation_enabled = opening_flag("choice_generation_enabled")
    final_anti_cheat_enabled = opening_flag("anti_cheat_enabled")
    session = ChatSessionModel(title=payload.title or "新对话")
    db.add(session)
    db.flush()
    world_template = None
    if final_template_id and final_template_id != "custom":
        world_template = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == final_template_id))
        if world_template is None:
            raise HTTPException(status_code=404, detail="选中的世界模板不存在")
    from ..services.unified_world_service import mapped_world_id
    selected_encyclopedia_id = payload.encyclopedia_id
    if selected_encyclopedia_id is None and world_template is not None:
        selected_encyclopedia_id = mapped_world_id(db, world_template.template_id)
    if selected_encyclopedia_id is not None:
        encyclopedia = db.execute(
            select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.id == selected_encyclopedia_id)
        ).scalar_one_or_none()
        if encyclopedia is None:
            raise HTTPException(status_code=404, detail="选中的世界百科库不存在")
    else:
        encyclopedia = None

    inherited_world_prompt = ""
    inherited_gameplay = payload.gameplay_mode or (world_template.gameplay_mode if world_template else "自由剧情")
    inherited_anti_cheat = ""
    if encyclopedia:
        inherited_world_prompt = encyclopedia.world_prompt or ""
        inherited_gameplay = encyclopedia.gameplay_mode or inherited_gameplay
        inherited_anti_cheat = encyclopedia.anti_cheat_prompt or ""

    db.add(
        SessionWorldModel(
            session_id=session.id,
            encyclopedia_id=selected_encyclopedia_id,
            template_id=final_template_id,
            gameplay_mode=inherited_gameplay,
            narrator_enabled=final_narrator_enabled,
            narrator_name=payload.narrator_name,
            choice_generation_enabled=final_choice_generation_enabled,
            max_choice_count=payload.max_choice_count,
            anti_cheat_enabled=final_anti_cheat_enabled,
            suggested_choices_json=[],
            anti_cheat_prompt=inherited_anti_cheat,
            world_prompt=inherited_world_prompt)
    )
    for sort_order, cid in enumerate(character_ids):
        db.add(
            SessionParticipantModel(
                session_id=session.id,
                character_id=cid,
                sort_order=sort_order,
            )
        )
        ensure_session_character_state(db, session.id, cid)
    db.commit()
    db.refresh(session, attribute_names=["world"])
    return {
        "id": session.id,
        "title": session.title,
        "summary": session.summary,
        "created_at": session.created_at,
        "updated_at": session.updated_at,
        "message_count": 0,
        "participant_count": len(character_ids),
        "think_max_enabled": bool(session.think_max_enabled),
        "last_message_preview": None,
        "world": session.world,
    }


@router.post("/import-archive", summary="导入可移植会话 ZIP")
async def import_session_archive_route(
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    """导入明文会话交换包；固定路径必须位于动态 session_id 路由之前。"""
    archive_bytes = await file.read(50 * 1024 * 1024 + 1)
    try:
        return import_session_archive(db, archive_bytes)
    except SessionExchangeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/{session_id}", summary="获取会话详情", response_model=SessionRead)
def get_session(session_id: int, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    return {
        "id": session.id,
        "title": session.title,
        "summary": session.summary,
        "created_at": session.created_at,
        "updated_at": session.updated_at,
        "message_count": db.scalar(select(func.count()).where(MessageModel.session_id == session_id)) or 0,
        "participant_count": db.scalar(select(func.count()).where(SessionParticipantModel.session_id == session_id)) or 0,
        "think_max_enabled": bool(session.think_max_enabled),
        "last_message_preview": last_message_previews_by_session_ids(db, [session_id]).get(session_id),
        "world": session.world,
    }


@router.put("/{session_id}", summary="更新会话信息", response_model=SessionRead)
def update_session(session_id: int, payload: SessionUpdate, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if payload.title is not None:
        session.title = payload.title
    if payload.think_max_enabled is not None:
        runtime = get_local_config(db)
        if payload.think_max_enabled and not bool(runtime.get("allow_session_think_max", False)):
            raise HTTPException(
                status_code=400,
                detail="请先在「设置 → 公共 API」中开启「允许对话页使用思考/Max 模式」后，再打开本会话开关。",
            )
        session.think_max_enabled = payload.think_max_enabled
    db.commit()
    db.refresh(session)
    return {
        "id": session.id,
        "title": session.title,
        "summary": session.summary,
        "created_at": session.created_at,
        "updated_at": session.updated_at,
        "message_count": db.scalar(select(func.count()).where(MessageModel.session_id == session_id)) or 0,
        "participant_count": db.scalar(select(func.count()).where(SessionParticipantModel.session_id == session_id)) or 0,
        "think_max_enabled": bool(session.think_max_enabled),
        "last_message_preview": last_message_previews_by_session_ids(db, [session_id]).get(session_id),
        "world": session.world,
    }

@router.delete("/{session_id}", summary="删除会话")
def delete_session(session_id: int, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    db.delete(session)
    db.commit()
    return {"ok": True}


@router.get("/{session_id}/messages", response_model=MessagePage)
def get_messages(
    session_id: int,
    cursor: int | None = None,
    after: int | None = None,
    limit: int = 40,
    branch_id: str = "main",
    db: Session = Depends(get_db),
):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if cursor is not None and after is not None:
        raise HTTPException(status_code=400, detail="cursor 与 after 不能同时使用")
    try:
        page_limit = min(max(limit, 10), 100)
        if after is not None:
            rows, next_cursor = get_session_messages_after_page(db, session_id, after, page_limit, branch_id)
        else:
            rows, next_cursor = get_session_messages_page(db, session_id, cursor, page_limit, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    return {
        "items": [serialize_message(row) for row in rows],
        "next_cursor": next_cursor,
    }


@router.get("/{session_id}/messages/window", response_model=MessageWindowPage)
def get_message_window(
    session_id: int,
    anchor_id: int,
    radius: int = 20,
    branch_id: str = "main",
    db: Session = Depends(get_db),
):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        rows, older_cursor, newer_cursor = get_session_message_window(
            db,
            session_id,
            anchor_id,
            min(max(radius, 5), 50),
            branch_id,
        )
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    if not rows:
        raise HTTPException(status_code=404, detail="消息已删除或不在当前故事线")
    return {
        "items": [serialize_message(row) for row in rows],
        "older_cursor": older_cursor,
        "newer_cursor": newer_cursor,
    }


@router.put("/{session_id}/messages/{message_id}", summary="编辑消息并创建剧情分支", response_model=MessageRead)
def update_message(session_id: int, message_id: int, payload: SessionMessageEdit, db: Session = Depends(get_db)):
    begin_storyline_write(db)
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    parent_branch_id = (payload.branch_id or "main").strip() or "main"
    try:
        message = get_visible_message(db, session_id, message_id, parent_branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    if message is None:
        raise HTTPException(status_code=404, detail="消息不在当前分支中")
    if not payload.content.strip():
        raise HTTPException(status_code=400, detail="消息内容不能为空")
    if payload.content == message.content:
        raise HTTPException(status_code=400, detail="消息内容没有变化")

    branch_id = f"edit_{message.id}_{uuid4().hex[:12]}"
    db.add(
        SessionBranchModel(
            session_id=session_id,
            branch_id=branch_id,
            label=f"已编辑 · 从消息 {message.id} 分叉",
            source_message_id=message.id,
            parent_branch_id=parent_branch_id,
        )
    )
    # SessionLocal 使用 autoflush=False；替代消息写入前必须让分支 owner 可见，
    # 但仍与替代消息保持在同一个数据库事务中。
    db.flush()
    structured_content = message.structured_content or {}
    replacement_content = payload.content
    if message.speaker_type != "user":
        from ..services.chat_service import parse_structured_reply
        structured_content = parse_structured_reply(payload.content)
        replacement_content = structured_content["raw"]
    attachments = [
        {
            "asset_type": item.asset_type,
            "file_name": item.file_name,
            "mime_type": item.mime_type,
            "storage_path": item.storage_path,
        }
        for item in message.attachments
    ]
    replacement = create_message(
        db,
        session_id=session_id,
        speaker_type=message.speaker_type,
        character_id=message.character_id,
        content=replacement_content,
        structured_content=structured_content,
        attachments=attachments,
        branch_id=branch_id,
        parent_message_id=message.parent_message_id,
        regenerated_from_message_id=message.id,
    )
    return serialize_message(replacement)


def _message_for_deletion(db: Session, session_id: int, message_id: int, branch_id: str | None):
    if branch_id is not None:
        try:
            message = get_visible_message(db, session_id, message_id, branch_id)
        except BranchContextError as exc:
            _raise_branch_http_error(exc)
    else:
        message = db.scalar(select(MessageModel).where(MessageModel.id == message_id, MessageModel.session_id == session_id))
    if message is None:
        raise HTTPException(status_code=404, detail="消息不存在或不在当前故事线中")
    return message


@router.get("/{session_id}/messages/{message_id}/deletion-impact")
def preview_message_deletion(session_id: int, message_id: int, branch_id: str = "main", db: Session = Depends(get_db)):
    return deletion_impact(db, _message_for_deletion(db, session_id, message_id, branch_id))


@router.delete("/{session_id}/messages/{message_id}", summary="删除未被故事线起点引用的消息")
def delete_message(session_id: int, message_id: int, db: Session = Depends(get_db), branch_id: str | None = None):
    begin_storyline_write(db)
    message = _message_for_deletion(db, session_id, message_id, branch_id)
    impact = remove_unreferenced_message(db, message)
    if not impact['can_delete']:
        db.rollback()
        raise HTTPException(status_code=409, detail=impact['reason'])
    return {"ok": True}


@router.post("/{session_id}/messages/{message_id}/swipe", summary="已停用的旧版 Swipe")
def swipe_message(session_id: int, message_id: int):
    """阻止旧客户端继续写入无法表达激活版本的 Swipe 消息。"""
    del session_id, message_id
    raise HTTPException(
        status_code=410,
        detail="Swipe 已停用，请使用“重新生成”创建新的剧情分支",
    )


@router.get("/{session_id}/messages/search-page", response_model=MessageSearchPageRead)
def search_message_results(session_id: int, q: str = "", before: int | None = None, limit: int = 25,
                           branch_id: str = "main", advance_index: bool = True, db: Session = Depends(get_db)):
    from ..services.message_search_service import search_message_page
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(404, "会话不存在")
    try:
        return search_message_page(db, session_id, q, branch_id, before, limit, advance_index)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@router.post("/{session_id}/messages/search-index/rebuild")
def rebuild_message_search(session_id: int, db: Session = Depends(get_db)):
    from ..services.message_search_service import rebuild_message_search_index
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(404, "会话不存在")
    try:
        rebuild_message_search_index(db)
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc
    return {"ok": True}


@router.get("/{session_id}/messages/search", response_model=list[MessageSearchHitRead])
def search_messages(session_id: int, q: str = "", limit: int = 40, branch_id: str = "main", db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        return search_session_messages(db, session_id, q, limit, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)


@router.get("/{session_id}/token-usage")
def get_session_token_usage(session_id: int, branch_id: str = "main", db: Session = Depends(get_db)):
    from ..services.context_service import get_token_usage_stats
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        return get_token_usage_stats(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)


@router.get("/{session_id}/branches", summary="获取分支列表", response_model=list[SessionBranchRead])
def list_session_branches(session_id: int, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    metadata = {
        row.branch_id: row
        for row in db.scalars(
            select(SessionBranchModel).where(SessionBranchModel.session_id == session_id)
        )
    }
    stmt = (
        select(
            MessageModel.branch_id,
            func.count(MessageModel.id).label("message_count"),
            func.max(MessageModel.id).label("latest_message_id"),
            func.max(MessageModel.created_at).label("latest_created_at"))
        .where(MessageModel.session_id == session_id)
        .group_by(MessageModel.branch_id)
        .order_by(func.max(MessageModel.id).desc())
    )
    branch_rows = db.execute(stmt).all()
    branch_stats = {
        (branch_id or "main"): (int(message_count or 0), latest_message_id, latest_created_at)
        for branch_id, message_count, latest_message_id, latest_created_at in branch_rows
    }
    ordered_branch_ids = [branch_id or "main" for branch_id, *_rest in branch_rows]
    for branch in sorted(metadata.values(), key=lambda item: (item.created_at, item.id), reverse=True):
        if branch.branch_id not in branch_stats:
            ordered_branch_ids.append(branch.branch_id)
    preview_message_ids = [item.source_message_id for item in metadata.values() if item.source_message_id]
    preview_map = (
        {
            row.id: row
            for row in db.scalars(select(MessageModel).where(MessageModel.id.in_(preview_message_ids)))
        }
        if preview_message_ids
        else {}
    )

    def compute_depth(branch_id: str) -> int:
        if branch_id == "main":
            return 0
        depth = 0
        current = metadata.get(branch_id)
        visited: set[str] = set()
        while current is not None and current.parent_branch_id and current.parent_branch_id != "main":
            if current.branch_id in visited:
                break
            visited.add(current.branch_id)
            depth += 1
            current = metadata.get(current.parent_branch_id)
        return depth + 1

    result = []
    for branch_id in ordered_branch_ids:
        branch = metadata.get(branch_id)
        source = preview_map.get(branch.source_message_id) if branch else None
        message_count, latest_message_id, latest_created_at = branch_stats.get(
            branch_id,
            (0, branch.source_message_id if branch else None, source.created_at if source else None),
        )
        result.append(
            {
                "branch_id": branch_id,
                "label": "主线" if branch_id == "main" else (branch.label if branch else branch_id),
                "source_message_id": None if branch_id == "main" else (branch.source_message_id if branch else None),
                "parent_branch_id": None if branch_id == "main" else (branch.parent_branch_id if branch else "main"),
                "source_message_preview": None if branch_id == "main" else (source.content[:120] if source else None),
                "source_created_at": None if branch_id == "main" else (source.created_at if source else None),
                "latest_created_at": latest_created_at,
                "depth": compute_depth(branch_id),
                "message_count": message_count,
                "latest_message_id": latest_message_id,
            }
        )
    return result


@router.post("/{session_id}/branches", summary="创建分支", response_model=SessionBranchRead)
def create_session_branch(session_id: int, payload: SessionBranchCreate, db: Session = Depends(get_db)):
    begin_storyline_write(db)
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    parent_branch_id = (payload.parent_branch_id or "main").strip() or "main"
    try:
        source_message = get_visible_message(db, session_id, payload.source_message_id, parent_branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    if source_message is None:
        raise HTTPException(status_code=400, detail="源消息不在父分支可见历史中")
    branch_id = payload.branch_id.strip() or f"branch_{payload.source_message_id}"
    if branch_id == "main":
        raise HTTPException(status_code=400, detail="main 是保留分支标识")
    exists = db.scalar(
        select(SessionBranchModel.id).where(
            SessionBranchModel.session_id == session_id,
            SessionBranchModel.branch_id == branch_id)
    )
    if exists is not None:
        raise HTTPException(status_code=400, detail="分支标识已存在")
    row = SessionBranchModel(
        session_id=session_id,
        branch_id=branch_id,
        label=payload.label.strip() or f"从消息 {payload.source_message_id} 分叉",
        source_message_id=payload.source_message_id,
        parent_branch_id=parent_branch_id)
    db.add(row)
    db.commit()
    return {
        "branch_id": row.branch_id,
        "label": row.label,
        "source_message_id": row.source_message_id,
        "parent_branch_id": row.parent_branch_id,
        "source_message_preview": source_message.content[:120],
        "source_created_at": source_message.created_at,
        "latest_created_at": source_message.created_at,
        "depth": 1,
        "message_count": 0,
        "latest_message_id": row.source_message_id,
    }


@router.post("/{session_id}/checkpoint")
def create_checkpoint(session_id: int, payload: dict, db: Session = Depends(get_db)):
    """创建命名检查点。在最近消息处创建一个标记为 checkponit 的分支。"""
    begin_storyline_write(db)
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    label = (payload.get("label") or "").strip() or f"检查点_{session_id}_{db.query(func.max(SessionBranchModel.id)).scalar() or 0 + 1}"
    parent_branch_id = payload.get("branch_id") or "main"
    try:
        tail_id = get_visible_tail_message_id(db, session_id, parent_branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    if tail_id is None:
        raise HTTPException(status_code=400, detail="会话没有可标记的消息")
    branch_token = f"ck_{session_id}_{int(time.time())}"
    row = SessionBranchModel(
        session_id=session_id,
        branch_id=branch_token,
        label=label,
        source_message_id=tail_id,
        parent_branch_id=parent_branch_id,
        is_checkpoint=True,
        checkpoint_label=label)
    db.add(row)
    db.commit()
    return {"branch_id": row.branch_id, "label": row.label, "source_message_id": row.source_message_id}


@router.get("/{session_id}/checkpoints")
def list_checkpoints(session_id: int, db: Session = Depends(get_db)):
    """列出所有检查点。"""
    rows = list(
        db.scalars(
            select(SessionBranchModel)
            .where(SessionBranchModel.session_id == session_id, SessionBranchModel.is_checkpoint == True)
            .order_by(SessionBranchModel.id.desc())
        )
    )
    return [{"branch_id": r.branch_id, "label": r.label, "source_message_id": r.source_message_id, "checkpoint_label": r.checkpoint_label or r.label} for r in rows]


@router.put("/{session_id}/messages/{message_id}/context")
def toggle_message_context(session_id: int, message_id: int, payload: MessageContextUpdate, db: Session = Depends(get_db)):
    """原文保留；上下文标志与派生记忆失效同事务提交。"""
    begin_storyline_write(db)
    msg = _message_for_deletion(db, session_id, message_id, payload.branch_id)
    changed = msg.include_in_context != payload.include_in_context
    if changed:
        if payload.expected_include_in_context is not None and msg.include_in_context != payload.expected_include_in_context:
            db.rollback()
            raise HTTPException(status_code=409, detail="这条消息的上下文状态已改变，请重新打开后操作")
        invalidate_source_memory(db, msg, memory_invalidation_plan(db, msg))
        msg.include_in_context = payload.include_in_context
    db.commit()
    return {"id": msg.id, "include_in_context": bool(msg.include_in_context), "changed": changed}


@router.post("/{session_id}/user-message")
def add_user_message(session_id: int, payload: SessionMessageCreate, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    branch_id = (payload.branch_id or "main").strip() or "main"
    try:
        parent_message_id = get_visible_tail_message_id(db, session_id, branch_id)
        message = create_message(
            db,
            session_id=session_id,
            speaker_type="user",
            content=payload.content,
            branch_id=branch_id,
            parent_message_id=parent_message_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    return serialize_message(message)


@router.get("/{session_id}/export")
def export_session_archive(session_id: int, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    zip_path = build_session_export_archive(db, session_id)
    return FileResponse(
        path=zip_path,
        filename=f"session_{session_id:04d}.zip",
        media_type="application/zip",
        background=BackgroundTask(zip_path.unlink, missing_ok=True),
    )


@router.get("/{session_id}/export-chat-html")
def export_session_chat_html(session_id: int, branch_id: str = "main", db: Session = Depends(get_db)):
    """只读导出当前分支对话为 HTML（UTF-8）。"""
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        messages = list_visible_messages(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    title = html.escape((session.title or f"会话 {session_id}").strip() or f"会话 {session_id}")
    safe_branch = "".join(c if c.isalnum() or c in "-_" else "_" for c in branch_id)[:80] or "main"
    parts = [
        "<!DOCTYPE html>",
        '<html lang="zh-CN"><head><meta charset="utf-8">',
        f"<title>{title}</title>",
        "<style>body{font-family:system-ui,sans-serif;max-width:720px;margin:24px auto;line-height:1.5;color:#111;}"
        ".msg{margin:12px 0;padding:10px 12px;border-radius:8px;border:1px solid #e5e7eb;background:#fafafa;}"
        ".who{font-size:0.82rem;color:#6b7280;margin-bottom:6px;font-weight:600;}"
        "pre{white-space:pre-wrap;word-break:break-word;margin:0;font-family:inherit;font-size:0.95rem;}"
        ".user{background:#eff6ff;border-color:#bfdbfe;}"
        ".char{background:#f0fdf4;border-color:#bbf7d0;}"
        ".narr{background:#faf5ff;border-color:#e9d5ff;}</style>",
        "</head><body>",
        f"<h1>{title}</h1>",
        f"<p>分支：<code>{html.escape(branch_id)}</code> · {len(messages)} 条消息</p>",
    ]
    for msg in messages:
        if msg.speaker_type == "user":
            who = "玩家"
            cls = "user"
        elif msg.speaker_type == "narrator":
            who = "旁白"
            cls = "narr"
        else:
            who = msg.character.name if msg.character else "角色"
            cls = "char"
        who_esc = html.escape(who)
        content_esc = html.escape(msg.content or "")
        parts.append(f'<div class="msg {cls}"><div class="who">{who_esc}</div><pre>{content_esc}</pre></div>')
    parts.append("</body></html>")
    body = "\n".join(parts).encode("utf-8")
    ascii_name = f"session_{session_id:04d}_{safe_branch}.html"
    disp = f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(ascii_name)}"
    return Response(content=body, media_type="text/html; charset=utf-8", headers={"Content-Disposition": disp})


@router.post("/backup/project")
def export_project_backup(
    db: Session = Depends(get_db),
    local_action: str | None = Header(default=None, alias="X-MoJing-Local-Action"),
):
    if local_action != BACKUP_ACTION_HEADER:
        raise HTTPException(status_code=403, detail="备份操作缺少本机请求标记。")
    bind = db.get_bind()
    if bind.dialect.name != "sqlite" or not bind.url.database or bind.url.database == ":memory:":
        raise HTTPException(status_code=409, detail="当前数据库不是可备份的本地 SQLite 文件。")
    database_path = Path(bind.url.database).expanduser().resolve()
    try:
        zip_path = build_portable_project_backup(database_path)
    except BackupError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return FileResponse(path=zip_path, filename=zip_path.name, media_type="application/zip")


@router.post("/backup/project/validate")
def validate_project_backup(
    file: UploadFile = File(...),
    local_action: str | None = Header(default=None, alias="X-MoJing-Local-Action"),
):
    if local_action != BACKUP_ACTION_HEADER:
        raise HTTPException(status_code=403, detail="备份预检缺少本机请求标记。")
    filename = Path(file.filename or "backup.zip").name
    if not filename.lower().endswith(".zip"):
        raise HTTPException(status_code=400, detail="请选择 .zip 格式的墨境备份。")
    try:
        with tempfile.TemporaryDirectory(prefix="mojing-backup-upload-") as temp_root:
            upload_path = Path(temp_root) / "backup.zip"
            with upload_path.open("wb") as target:
                shutil.copyfileobj(file.file, target, length=1024 * 1024)
            return validate_portable_project_backup(upload_path)
    except BackupError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/{session_id}/user-message/upload")
def add_user_message_with_files(
    session_id: int,
    content: str = Form(""),
    branch_id: str = Form("main"),
    files: list[UploadFile] = File(default_factory=list),
    db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if not content.strip() and not files:
        raise HTTPException(status_code=400, detail="文本和附件不能同时为空")

    try:
        resolve_branch_context(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)

    upload_dir = STORAGE_DIR / "uploads" / f"session_{session_id:04d}"
    upload_dir.mkdir(parents=True, exist_ok=True)
    attachments = []
    created_paths: list[Path] = []
    limit_mb, max_bytes = _chat_upload_limit(db)
    try:
        for file in files:
            target = _write_chat_upload(file, upload_dir, max_bytes, limit_mb)
            created_paths.append(target)
            mime = file.content_type or "application/octet-stream"
            asset_type = "image" if mime.startswith("image/") else "file"
            attachments.append(
                {
                    "asset_type": asset_type,
                    "file_name": file.filename or target.name,
                    "mime_type": mime,
                    "storage_path": str(target.relative_to(STORAGE_DIR)).replace("\\", "/"),
                }
            )

        message = create_message(
            db,
            session_id=session_id,
            speaker_type="user",
            content=content,
            attachments=attachments,
            branch_id=branch_id or "main",
            parent_message_id=get_visible_tail_message_id(db, session_id, branch_id or "main"))
        return serialize_message(message)
    except Exception:
        db.rollback()
        for path in created_paths:
            path.unlink(missing_ok=True)
        raise


@router.get("/{session_id}/participants", summary="获取参与者列表", response_model=list[ParticipantRead])
def list_participants(session_id: int, db: Session = Depends(get_db)):
    rows = list(
        db.scalars(
            select(SessionParticipantModel)
            .options(joinedload(SessionParticipantModel.character).joinedload(CharacterModel.voice_profile))
            .where(SessionParticipantModel.session_id == session_id)
            .order_by(SessionParticipantModel.sort_order.asc(), SessionParticipantModel.id.asc())
        )
    )
    return rows


@router.post("/{session_id}/participants", summary="添加角色", response_model=list[ParticipantRead])
def add_participant(session_id: int, payload: SessionParticipantCreate, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    character = db.get(CharacterModel, payload.character_id)
    if session is None or character is None:
        raise HTTPException(status_code=404, detail="会话或人物不存在")

    exists = db.scalar(
        select(SessionParticipantModel.id).where(
            SessionParticipantModel.session_id == session_id,
            SessionParticipantModel.character_id == payload.character_id)
    )
    if exists is None:
        max_order = db.scalar(
            select(func.coalesce(func.max(SessionParticipantModel.sort_order), -1)).where(
                SessionParticipantModel.session_id == session_id
            )
        )
        db.add(
            SessionParticipantModel(
                session_id=session_id,
                character_id=payload.character_id,
                sort_order=int(max_order or -1) + 1)
        )
        db.commit()
    ensure_session_character_state(db, session_id, payload.character_id)
    return list_participants(session_id, db)


@router.patch(
    "/{session_id}/participants/{character_id}",
    summary="更新参与者（发言率等）",
    response_model=list[ParticipantRead],
)
def update_participant(
    session_id: int,
    character_id: int,
    payload: SessionParticipantUpdate,
    db: Session = Depends(get_db),
):
    row = db.scalar(
        select(SessionParticipantModel).where(
            SessionParticipantModel.session_id == session_id,
            SessionParticipantModel.character_id == character_id,
        )
    )
    if row is None:
        raise HTTPException(status_code=404, detail="人物未绑定到当前会话")
    if payload.talkativeness is not None:
        row.talkativeness = float(payload.talkativeness)
    db.commit()
    return list_participants(session_id, db)


@router.delete("/{session_id}/participants/{character_id}", summary="移除角色", response_model=list[ParticipantRead])
def remove_participant(session_id: int, character_id: int, db: Session = Depends(get_db)):
    row = db.scalar(
        select(SessionParticipantModel).where(
            SessionParticipantModel.session_id == session_id,
            SessionParticipantModel.character_id == character_id)
    )
    if row is None:
        raise HTTPException(status_code=404, detail="人物未绑定到当前会话")
    db.delete(row)
    db.commit()
    return list_participants(session_id, db)


@router.get("/{session_id}/world", summary="获取世界配置", response_model=SessionWorldRead)
def get_session_world(session_id: int, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == session_id))
    if world is None:
        world = SessionWorldModel(session_id=session_id, narrator_enabled=False, narrator_name="旁白")
        db.add(world)
        db.commit()
        db.refresh(world)
    return world

@router.put("/{session_id}/world", summary="更新世界配置", response_model=SessionWorldRead)
def update_session_world(session_id: int, payload: SessionWorldUpdate, db: Session = Depends(get_db)):
    world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == session_id))
    if world is None:
        world = SessionWorldModel(session_id=session_id)
        db.add(world)
    if payload.template_id and payload.template_id != "custom":
        exists = db.scalar(select(WorldTemplateModel.id).where(WorldTemplateModel.template_id == payload.template_id))
        if exists is None:
            raise HTTPException(status_code=404, detail="选中的世界模板不存在")
    if payload.encyclopedia_id is not None:
        encyclopedia_exists = db.scalar(select(WorldEncyclopediaModel.id).where(WorldEncyclopediaModel.id == payload.encyclopedia_id))
        if encyclopedia_exists is None:
            raise HTTPException(status_code=404, detail="选中的世界百科库不存在")
    world.encyclopedia_id = payload.encyclopedia_id
    world.world_prompt = payload.world_prompt
    world.template_id = payload.template_id
    world.gameplay_mode = payload.gameplay_mode
    world.narrator_enabled = payload.narrator_enabled
    world.narrator_name = payload.narrator_name
    world.choice_generation_enabled = payload.choice_generation_enabled
    world.max_choice_count = payload.max_choice_count
    world.suggested_choices_json = payload.suggested_choices_json
    world.anti_cheat_enabled = payload.anti_cheat_enabled
    world.anti_cheat_prompt = payload.anti_cheat_prompt
    db.commit()
    db.refresh(world)
    return world


@router.get("/{session_id}/character-states", summary="获取角色状态列表", response_model=list[SessionCharacterStateRead])
def list_character_states(session_id: int, db: Session = Depends(get_db)):
    participant_ids = list(
        db.scalars(select(SessionParticipantModel.character_id).where(SessionParticipantModel.session_id == session_id))
    )
    for character_id in participant_ids:
        ensure_session_character_state(db, session_id, int(character_id))
    return list(
        db.scalars(
            select(SessionCharacterStateModel)
            .where(SessionCharacterStateModel.session_id == session_id)
            .order_by(SessionCharacterStateModel.id.asc())
        )
    )


@router.get("/{session_id}/model-choice")
def read_model_choice(session_id: int, db: Session = Depends(get_db)):
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(404, "会话不存在")
    return get_model_choice(db, session_id)


@router.put("/{session_id}/model-choice")
def update_model_choice(session_id: int, payload: ModelChoiceWrite, db: Session = Depends(get_db)):
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(404, "会话不存在")
    get_model_choice(db, session_id)  # Reject unreadable future versions before overwriting.
    if payload.selection:
        resolve_selection(db, payload.selection)
    return set_setting(db, f"chat_model_choice_{session_id}", {"version": 1, **payload.model_dump()})


@router.post("/{session_id}/speaker-plan", summary="获取发言人规划", response_model=SpeakerPlanRead)
def get_speaker_plan(session_id: int, payload: GenerateRequest, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        resolve_branch_context(db, session_id, payload.branch_id or "main")
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    ids, reason = select_speakers_for_turn(
        db,
        session_id,
        payload.user_message or "",
        payload.max_auto_speakers,
        payload.branch_id or "main")
    return {"character_ids": ids, "reason": reason}


@router.post("/{session_id}/generate/stream")
def generate_stream(session_id: int, payload: GenerateRequest, db: Session = Depends(get_db)):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")

    # Resolve once before any message write. Every speaker and narrator in this
    # response uses this immutable tuple, even if settings change during SSE.
    choice = get_model_choice(db, session_id)["selection"]
    route_args = {"text_config": resolve_selection(db, ModelSelection.model_validate(choice))} if choice else {}

    branch_id = payload.branch_id or "main"
    try:
        # 先完成分支校验，再允许任何本轮消息落库。
        resolve_branch_context(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)

    if payload.user_message:
        create_message(
            db,
            session_id=session_id,
            speaker_type="user",
            content=payload.user_message,
            branch_id=branch_id,
            parent_message_id=get_visible_tail_message_id(db, session_id, branch_id))

    if payload.narrator_only:
        def narrator_only_stream():
            yield sse_event({"type": "session", "session_id": session_id})
            yield sse_event({"type": "speaker_plan", "character_ids": [], "reason": "仅生成旁白"})
            for event in stream_narrator_reply(session_id, branch_id, **route_args):
                yield sse_event(event)
            trigger_memory_compaction_async(session_id, branch_id)
            yield sse_event({"type": "done"})

        return StreamingResponse(narrator_only_stream(), media_type="text/event-stream")

    if payload.auto_select_speakers:
        target_ids, plan_reason = select_speakers_for_turn(
            db,
            session_id,
            payload.user_message or "",
            payload.max_auto_speakers,
            payload.branch_id or "main")
    elif payload.character_ids:
        target_ids = payload.character_ids
        plan_reason = "使用手动勾选的人物列表。"
    else:
        target_ids = list(
            db.scalars(
                select(SessionParticipantModel.character_id)
                .where(SessionParticipantModel.session_id == session_id)
                .order_by(SessionParticipantModel.sort_order.asc(), SessionParticipantModel.id.asc())
            )
        )
        plan_reason = "未启用自动调度，使用当前会话全部人物。"

    if not target_ids:
        # 通过 SSE 流式返回错误，而不是 HTTP 400，
        # 因为用户消息可能已写入数据库（L732-740），需要保持一致性
        def empty_error_stream():
            yield sse_event({"type": "session", "session_id": session_id})
            yield sse_event({"type": "error", "message": "当前会话还没有绑定人物，请先在角色页添加角色到会话中。"})
            yield sse_event({"type": "done"})
        return StreamingResponse(empty_error_stream(), media_type="text/event-stream")

    def event_stream():
        yield sse_event({"type": "session", "session_id": session_id})
        yield sse_event({"type": "speaker_plan", "character_ids": target_ids, "reason": plan_reason})
        for character_id in target_ids:
            for event in stream_character_reply(session_id, character_id, branch_id, **route_args):
                yield sse_event(event)
        if payload.include_narrator:
            for event in stream_narrator_reply(session_id, branch_id, **route_args):
                yield sse_event(event)
        trigger_memory_compaction_async(session_id, branch_id)
        yield sse_event({"type": "done"})

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@router.get("/{session_id}/bookmarks", summary="获取收藏列表")
def list_bookmarks(session_id: int, db: Session = Depends(get_db)):
    return list(
        db.scalars(
            select(MessageBookmarkModel)
            .where(MessageBookmarkModel.session_id == session_id)
            .order_by(MessageBookmarkModel.created_at.desc())
        )
    )


@router.post("/{session_id}/bookmarks")
def add_bookmark(session_id: int, payload: dict, db: Session = Depends(get_db)):
    message_id = payload.get("message_id")
    if not message_id:
        raise HTTPException(status_code=400, detail="message_id 不能为空")
    existing = db.scalar(
        select(MessageBookmarkModel).where(
            MessageBookmarkModel.session_id == session_id,
            MessageBookmarkModel.message_id == message_id)
    )
    if existing:
        db.delete(existing)
        db.commit()
        return {"ok": True, "bookmarked": False}
    bookmark = MessageBookmarkModel(session_id=session_id, message_id=message_id, note=payload.get("note", ""))
    db.add(bookmark)
    db.commit()
    return {"ok": True, "bookmarked": True}


@router.delete("/{session_id}/bookmarks/{bookmark_id}", summary="取消收藏")
def remove_bookmark(session_id: int, bookmark_id: int, db: Session = Depends(get_db)):
    bookmark = db.get(MessageBookmarkModel, bookmark_id)
    if not bookmark:
        raise HTTPException(status_code=404, detail="书签未找到")
    db.delete(bookmark)
    db.commit()
    return {"ok": True}


def _expand_memory_correction_trace(
    db: Session,
    session_id: int,
    refs: object,
) -> list[dict]:
    if not isinstance(refs, list):
        return []
    normalized: list[tuple[int, str | None]] = []
    for ref in refs:
        raw_id = ref.get("id") if isinstance(ref, dict) else ref
        recorded_updated_at = ref.get("updated_at") if isinstance(ref, dict) else None
        try:
            correction_id = int(raw_id)
        except (TypeError, ValueError):
            continue
        if correction_id > 0:
            normalized.append((correction_id, recorded_updated_at if isinstance(recorded_updated_at, str) else None))
    if not normalized:
        return []

    correction_ids = {item[0] for item in normalized}
    rows = db.scalars(
        select(SessionMemoryCorrectionModel).where(
            SessionMemoryCorrectionModel.session_id == session_id,
            SessionMemoryCorrectionModel.id.in_(correction_ids),
        )
    ).all()
    by_id = {row.id: row for row in rows}
    result: list[dict] = []
    for order, (correction_id, recorded_updated_at) in enumerate(normalized, start=1):
        row = by_id.get(correction_id)
        if row is None:
            result.append(
                {
                    "id": correction_id,
                    "order": order,
                    "content": None,
                    "branch_id": None,
                    "source_message_id": None,
                    "status": "deleted",
                    "reason": "用户锁定内容，冲突时优先于自动记忆和模型推测。",
                }
            )
            continue
        current_updated_at = row.updated_at.isoformat()
        status = (
            "unknown_revision"
            if recorded_updated_at is None
            else "current"
            if recorded_updated_at == current_updated_at
            else "changed"
        )
        result.append(
            {
                "id": row.id,
                "order": order,
                "content": row.content,
                "branch_id": row.branch_id,
                "source_message_id": row.source_message_id,
                "status": status,
                "reason": "用户锁定内容，冲突时优先于自动记忆和模型推测。",
            }
        )
    return result


@router.get("/{session_id}/prompt-trace/latest")
def get_latest_prompt_trace(
    session_id: int,
    branch_id: str = "main",
    db: Session = Depends(get_db),
):
    """获取当前故事线最近一条 AI 生成消息的 Prompt 编排追踪信息。"""
    try:
        resolve_branch_context(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    latest_msg = db.scalars(
        select(MessageModel)
        .where(
            MessageModel.session_id == session_id,
            MessageModel.branch_id == branch_id,
            MessageModel.speaker_type.in_(["character", "narrator"]))
        .order_by(MessageModel.id.desc())
    ).first()
    if not latest_msg or not latest_msg.structured_content:
        return None
    debug = latest_msg.structured_content.get("prompt_debug")
    if not debug:
        return None
    result = dict(debug)
    result["message_id"] = latest_msg.id
    result["memory_corrections_recorded"] = isinstance(debug.get("memory_corrections"), list)
    result["memory_corrections"] = _expand_memory_correction_trace(
        db,
        session_id,
        debug.get("memory_corrections"),
    )
    return result


@router.get("/{session_id}/memory-segments")
def get_memory_segments(
    session_id: int,
    branch_id: str = "main",
    db: Session = Depends(get_db),
    limit: int = Query(100, ge=1, le=200),
):
    """获取会话的记忆分段列表。"""
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        from ..services.memory_v2_service import get_visible_memory_segments
        return get_visible_memory_segments(db, session_id, branch_id, limit=limit)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)


def _validate_memory_correction_scope(
    session_id: int,
    payload: SessionMemoryCorrectionWrite,
    db: Session,
    *,
    existing_source_message_id: int | None = None,
) -> str | None:
    branch_id = payload.branch_id.strip() if payload.branch_id is not None else None
    if branch_id == "":
        branch_id = None
    if branch_id is not None:
        try:
            resolve_branch_context(db, session_id, branch_id)
        except BranchContextError as exc:
            _raise_branch_http_error(exc)
    if payload.source_message_id is not None:
        source = db.scalar(
            select(MessageModel).where(
                MessageModel.id == payload.source_message_id,
                MessageModel.session_id == session_id,
            )
        )
        if source is None and payload.source_message_id != existing_source_message_id:
            raise HTTPException(status_code=400, detail="source_message_id 不属于当前会话")
    return branch_id


@router.get("/{session_id}/memory-corrections", response_model=list[SessionMemoryCorrectionRead])
def list_memory_corrections(session_id: int, branch_id: str = "main", db: Session = Depends(get_db)):
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    try:
        resolve_branch_context(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)
    from ..services.memory_v2_service import get_active_memory_corrections
    return get_active_memory_corrections(db, session_id, branch_id)


@router.post("/{session_id}/memory-corrections", response_model=SessionMemoryCorrectionRead, status_code=201)
def create_memory_correction(session_id: int, payload: SessionMemoryCorrectionWrite, db: Session = Depends(get_db)):
    if db.get(ChatSessionModel, session_id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    branch_id = _validate_memory_correction_scope(session_id, payload, db)
    row = SessionMemoryCorrectionModel(
        session_id=session_id,
        branch_id=branch_id,
        content=payload.content,
        source_message_id=payload.source_message_id,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


@router.put("/{session_id}/memory-corrections/{correction_id}", response_model=SessionMemoryCorrectionRead)
def update_memory_correction(session_id: int, correction_id: int, payload: SessionMemoryCorrectionWrite, db: Session = Depends(get_db)):
    row = db.scalar(
        select(SessionMemoryCorrectionModel).where(
            SessionMemoryCorrectionModel.id == correction_id,
            SessionMemoryCorrectionModel.session_id == session_id,
        )
    )
    if row is None:
        raise HTTPException(status_code=404, detail="记忆纠正不存在")
    row.branch_id = _validate_memory_correction_scope(
        session_id,
        payload,
        db,
        existing_source_message_id=row.source_message_id,
    )
    row.content = payload.content
    row.source_message_id = payload.source_message_id
    db.commit()
    db.refresh(row)
    return row


@router.delete("/{session_id}/memory-corrections/{correction_id}")
def delete_memory_correction(session_id: int, correction_id: int, db: Session = Depends(get_db)):
    row = db.scalar(
        select(SessionMemoryCorrectionModel).where(
            SessionMemoryCorrectionModel.id == correction_id,
            SessionMemoryCorrectionModel.session_id == session_id,
        )
    )
    if row is None:
        raise HTTPException(status_code=404, detail="记忆纠正不存在")
    db.delete(row)
    db.commit()
    return {"ok": True}


@router.get("/{session_id}/event-tree")
def get_event_tree(session_id: int, branch_id: str = "main", db: Session = Depends(get_db)):
    """获取会话的事件节点列表（因果树）。"""
    from ..models import SessionEventNodeModel
    nodes = list(
        db.scalars(
            select(SessionEventNodeModel)
            .where(
                SessionEventNodeModel.session_id == session_id,
                SessionEventNodeModel.branch_id == branch_id,
            )
            .order_by(SessionEventNodeModel.created_at.asc(), SessionEventNodeModel.id.asc())
        )
    )
    return nodes


@router.post("/{session_id}/import-tavern-chat", summary="单向导入酒馆格式聊天记录（mes[] 或 JSONL）")
async def import_tavern_chat_session(
    session_id: int,
    character_id: int = Form(...),
    branch_id: str = Form("main"),
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    session = db.get(ChatSessionModel, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if db.get(CharacterModel, character_id) is None:
        raise HTTPException(status_code=404, detail="角色不存在")
    part = db.scalar(
        select(SessionParticipantModel).where(
            SessionParticipantModel.session_id == session_id,
            SessionParticipantModel.character_id == character_id,
        )
    )
    if part is None:
        raise HTTPException(status_code=400, detail="该角色未加入此会话，请先添加参与者")
    try:
        branch_context = resolve_branch_context(db, session_id, branch_id)
    except BranchContextError as exc:
        _raise_branch_http_error(exc)

    raw_bytes = await file.read()
    try:
        raw = raw_bytes.decode("utf-8")
    except UnicodeDecodeError:
        raw = raw_bytes.decode("utf-8", errors="replace")
    rows = parse_tavern_chat_file(raw)
    if not rows:
        raise HTTPException(status_code=400, detail="未解析出任何消息（支持 JSON 根级 mes[] 或 JSONL）")

    parent_message_id = get_visible_tail_message_id(db, session_id, branch_context.branch_id)
    for row in rows:
        sp = row["speaker"]
        st = "user" if sp == "user" else "character"
        cid = None if st == "user" else character_id
        raw_obj = row.get("raw") or {}
        message = MessageModel(
            session_id=session_id,
            speaker_type=st,
            character_id=cid,
            branch_id=branch_context.branch_id,
            parent_message_id=parent_message_id,
            content=row["content"],
            structured_content={"st_import": raw_obj},
        )
        db.add(message)
        db.flush()
        parent_message_id = message.id
    session.updated_at = now_utc()
    db.commit()
    return {"imported": len(rows), "branch_id": branch_context.branch_id}
