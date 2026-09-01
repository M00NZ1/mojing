from __future__ import annotations

import json
import logging
import re
import shutil
import tempfile
import threading
import uuid
import zipfile
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session, joinedload, selectinload

from ..config import STORAGE_DIR
from ..models import (
    CharacterModel,
    CharacterProfileModel,
    ChatSessionModel,
    MessageModel,
    SessionCharacterStateModel,
    SessionBranchModel,
    SessionMemoryCorrectionModel,
    SessionParticipantModel,
    SessionWorldModel,
    WorldEncyclopediaModel,
    WorldLoreEntryModel,
    WorldTemplateModel,
)


EXPORT_ROOT = STORAGE_DIR / "conversations"
logger = logging.getLogger(__name__)

_session_export_locks: dict[int, threading.Lock] = {}
_session_export_locks_guard = threading.Lock()


class SnapshotPublishError(RuntimeError):
    """会话快照发布失败，消息中包含可恢复目录。"""


def _get_session_export_lock(session_id: int) -> threading.Lock:
    with _session_export_locks_guard:
        return _session_export_locks.setdefault(session_id, threading.Lock())


def export_session_snapshot(db: Session, session_id: int) -> None:
    """把数据库中的会话镜像完整生成后替换到固定目录。"""

    with _get_session_export_lock(session_id):
        _export_session_snapshot_unlocked(db, session_id)


def _export_session_snapshot_unlocked(db: Session, session_id: int) -> None:
    """调用方持有会话导出锁时构建并发布快照。"""

    session = db.get(ChatSessionModel, session_id)
    if session is None:
        return

    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    temp_dir = Path(
        tempfile.mkdtemp(prefix=f".convo_{session_id:04d}.tmp-", dir=str(EXPORT_ROOT))
    )
    try:
        _write_session_snapshot(db, session_id, temp_dir)
    except BaseException:
        _remove_created_directory(temp_dir, "未能清理构建失败的临时快照")
        raise

    base_dir = EXPORT_ROOT / f"convo_{session_id:04d}"
    old_dir = _unused_old_snapshot_path(session_id)
    old_snapshot_moved = False
    if base_dir.exists():
        try:
            base_dir.replace(old_dir)
            old_snapshot_moved = True
        except BaseException as exc:
            _remove_created_directory(temp_dir, "未能清理未发布的临时快照")
            raise SnapshotPublishError(
                f"无法暂存旧快照，原快照保持在 {base_dir}；未发布目录为 {temp_dir}"
            ) from exc

    try:
        temp_dir.replace(base_dir)
    except BaseException as publish_exc:
        if old_snapshot_moved:
            if base_dir.exists():
                raise SnapshotPublishError(
                    "新快照发布失败且目标路径意外存在；为避免删除未知目录，"
                    f"已保留目标 {base_dir}、旧快照 {old_dir} 和临时快照 {temp_dir}"
                ) from publish_exc
            try:
                old_dir.replace(base_dir)
            except BaseException as restore_exc:
                raise SnapshotPublishError(
                    "新快照发布失败且旧快照恢复失败；"
                    f"旧快照保留在 {old_dir}，临时快照保留在 {temp_dir}，目标路径为 {base_dir}"
                ) from restore_exc
        _remove_created_directory(temp_dir, "未能清理发布失败的临时快照")
        raise SnapshotPublishError(
            f"新快照发布失败；旧快照位于 {base_dir}，未发布目录为 {temp_dir}"
        ) from publish_exc

    if old_snapshot_moved:
        _remove_created_directory(old_dir, "已发布新快照，但未能清理旧快照")


def _remove_created_directory(path: Path, warning: str) -> None:
    """尽力删除当前调用创建或移动出的精确目录。"""

    if not path.exists():
        return
    try:
        shutil.rmtree(path)
    except OSError:
        logger.warning("%s：%s", warning, path, exc_info=True)


def _unused_old_snapshot_path(session_id: int) -> Path:
    """返回尚不存在的会话专属旧快照路径。"""

    while True:
        path = EXPORT_ROOT / f".convo_{session_id:04d}.old-{uuid.uuid4().hex}"
        if not path.exists():
            return path


def _write_session_snapshot(db: Session, session_id: int, base_dir: Path) -> None:
    """把数据库中的会话镜像写入一个尚未对外可见的目录。"""

    session = db.get(ChatSessionModel, session_id)
    if session is None:
        return

    for relative in [
        "meta",
        "messages",
        "summary",
        "characters",
    ]:
        (base_dir / relative).mkdir(parents=True, exist_ok=True)

    participants = list(
        db.scalars(
            select(SessionParticipantModel)
            .options(
                joinedload(SessionParticipantModel.character).joinedload(CharacterModel.profile),
            )
            .where(SessionParticipantModel.session_id == session_id)
            .order_by(SessionParticipantModel.sort_order.asc(), SessionParticipantModel.id.asc())
        )
    )
    messages = list(
            db.scalars(
                select(MessageModel)
                .options(joinedload(MessageModel.character))
                .where(MessageModel.session_id == session_id)
                .order_by(MessageModel.id.asc())
            ).unique()
        )
    branches = list(
        db.scalars(
            select(SessionBranchModel)
            .where(SessionBranchModel.session_id == session_id)
            .order_by(SessionBranchModel.created_at.asc(), SessionBranchModel.id.asc())
        )
    )
    memory_corrections = list(
        db.scalars(
            select(SessionMemoryCorrectionModel)
            .where(SessionMemoryCorrectionModel.session_id == session_id)
            .order_by(
                SessionMemoryCorrectionModel.created_at.asc(),
                SessionMemoryCorrectionModel.id.asc(),
            )
        )
    )
    world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == session_id))
    world_template = None
    lore_entries: list[WorldLoreEntryModel] = []
    if world and world.template_id and world.template_id != "custom":
        world_template = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == world.template_id))
        if world_template is not None:
            lore_entries = list(
                db.scalars(
                    select(WorldLoreEntryModel)
                    .where(WorldLoreEntryModel.world_template_id == world_template.id)
                    .order_by(WorldLoreEntryModel.sort_order.asc(), WorldLoreEntryModel.id.asc())
                )
            )
    encyclopedia = db.get(WorldEncyclopediaModel, world.encyclopedia_id) if world and world.encyclopedia_id else None

    (base_dir / "meta" / "session.json").write_text(
        json.dumps(
            {
                "id": session.id,
                "title": session.title,
                "summary": session.summary,
                "think_max_enabled": bool(session.think_max_enabled),
                "created_at": session.created_at.isoformat(),
                "updated_at": session.updated_at.isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (base_dir / "meta" / "participants.json").write_text(
        json.dumps(
            [
                {
                    "character_id": row.character_id,
                    "name": row.character.name if row.character else "",
                    "sort_order": row.sort_order,
                    "talkativeness": row.talkativeness,
                    "muted": bool(row.muted),
                    "force_next": bool(row.force_next),
                    "allow_self_response": bool(row.allow_self_response),
                    "speaker_strategy": row.speaker_strategy,
                }
                for row in participants
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (base_dir / "meta" / "branches.json").write_text(
        json.dumps(
            [
                {
                    "branch_id": item.branch_id,
                    "label": item.label,
                    "source_message_id": item.source_message_id,
                    "parent_branch_id": item.parent_branch_id,
                    "is_checkpoint": bool(item.is_checkpoint),
                    "checkpoint_label": item.checkpoint_label,
                    "created_at": item.created_at.isoformat(),
                }
                for item in branches
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (base_dir / "meta" / "memory_corrections.json").write_text(
        json.dumps(
            [
                {
                    "id": item.id,
                    "branch_id": item.branch_id,
                    "content": item.content,
                    "source_message_id": item.source_message_id,
                    "created_at": item.created_at.isoformat(),
                    "updated_at": item.updated_at.isoformat(),
                }
                for item in memory_corrections
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (base_dir / "summary" / "session_summary.md").write_text(session.summary or "", encoding="utf-8")
    (base_dir / "meta" / "world.json").write_text(
        json.dumps(
            {
                "template_id": world.template_id if world else "custom",
                "template_label": world_template.label if world_template else "",
                "encyclopedia_id": world.encyclopedia_id if world else None,
                "encyclopedia_name": encyclopedia.name if encyclopedia else "",
                "gameplay_mode": world.gameplay_mode if world else "自由剧情",
                "world_prompt": world.world_prompt if world else "",
                "narrator_enabled": world.narrator_enabled if world else False,
                "narrator_name": world.narrator_name if world else "旁白",
                "choice_generation_enabled": world.choice_generation_enabled if world else True,
                "max_choice_count": world.max_choice_count if world else 3,
                "suggested_choices": list(world.suggested_choices_json or []) if world else [],
                "anti_cheat_enabled": world.anti_cheat_enabled if world else True,
                "anti_cheat_prompt": world.anti_cheat_prompt if world else "",
                "world_timeline": list(world.world_timeline_json or []) if world else [],
                "auto_sediment_enabled": world.auto_sediment_enabled if world else True,
                "sediment_interval": world.sediment_interval if world else 20,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (base_dir / "summary" / "lorebook.json").write_text(
        json.dumps(
            [
                {
                    "title": item.title,
                    "entry_type": item.entry_type,
                    "keywords": list(item.keywords_json or []),
                    "content": item.content,
                    "sort_order": item.sort_order,
                    "is_core": item.is_core,
                }
                for item in lore_entries
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    messages_path = base_dir / "messages" / "messages.jsonl"
    with messages_path.open("w", encoding="utf-8") as handle:
        for message in messages:
            handle.write(
                json.dumps(
                    {
                        "id": message.id,
                        "speaker_type": message.speaker_type,
                        "character_id": message.character_id,
                        "branch_id": message.branch_id,
                        "parent_message_id": message.parent_message_id,
                        "regenerated_from_message_id": message.regenerated_from_message_id,
                        "swipe_group_id": message.swipe_group_id,
                        "include_in_context": bool(message.include_in_context),
                        "character_name": message.character.name if message.character else None,
                        "content": message.content,
                        "structured_content": message.structured_content or {},
                        "attachments": [
                            {
                                "id": item.id,
                                "asset_type": item.asset_type,
                                "file_name": item.file_name,
                                "mime_type": item.mime_type,
                            }
                            for item in message.attachments
                        ],
                        "created_at": message.created_at.isoformat(),
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )

    (base_dir / "manifest.json").write_text(
        json.dumps(
            {
                "format": "mojing_session_exchange",
                "version": 1,
                "contents": [
                    "meta/session.json",
                    "meta/participants.json",
                    "meta/branches.json",
                    "meta/memory_corrections.json",
                    "meta/world.json",
                    "summary/session_summary.md",
                    "summary/lorebook.json",
                    "messages/messages.jsonl",
                    "characters/",
                ],
                "media_included": False,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    for row in participants:
        if row.character is None:
            continue
        slug = _safe_slug(row.character.name)
        source_dir = base_dir / "characters" / slug / "source"
        memory_dir = base_dir / "characters" / slug / "memory"
        meta_dir = base_dir / "characters" / slug / "meta"
        source_dir.mkdir(parents=True, exist_ok=True)
        memory_dir.mkdir(parents=True, exist_ok=True)
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "character.json").write_text(
            json.dumps(
                {
                    "id": row.character.id,
                    "name": row.character.name,
                    "persona_prompt": row.character.persona_prompt,
                    "avatar_color": row.character.avatar_color,
                    "model_name": row.character.model_name,
                    "temperature": row.character.temperature,
                    "max_tokens": row.character.max_tokens,
                    "top_p": row.character.top_p,
                    "top_k": row.character.top_k,
                    "frequency_penalty": row.character.frequency_penalty,
                    "presence_penalty": row.character.presence_penalty,
                    "repetition_penalty": row.character.repetition_penalty,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        profile = row.character.profile
        state = db.scalar(
            select(SessionCharacterStateModel).where(
                SessionCharacterStateModel.session_id == session_id,
                SessionCharacterStateModel.character_id == row.character.id,
            )
        )
        if profile:
            (source_dir / "persona.txt").write_text(
                profile.raw_persona_text or "",
                encoding="utf-8",
            )
            (memory_dir / "character_card.json").write_text(
                json.dumps(profile.character_card_json or {}, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            (memory_dir / "character_card.md").write_text(profile.character_card_markdown or "", encoding="utf-8")
        if state:
            (memory_dir / "dynamic_state.json").write_text(
                json.dumps(state.dynamic_state_json or {}, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            (memory_dir / "relations.json").write_text(
                json.dumps(state.relations_json or {}, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            (memory_dir / "private_memory.jsonl").write_text(
                "\n".join(json.dumps(item, ensure_ascii=False) for item in (state.private_facts_json or [])),
                encoding="utf-8",
            )
            (memory_dir / "event_log.jsonl").write_text(
                "\n".join(json.dumps(item, ensure_ascii=False) for item in (state.event_log_json or [])),
                encoding="utf-8",
            )
def _safe_slug(value: str) -> str:
    normalized = re.sub(r"[^\w\u4e00-\u9fff-]+", "_", value.strip(), flags=re.UNICODE)
    return normalized[:64] or "character"


def build_session_export_zip(session_id: int) -> Path:
    """把会话镜像目录打包成 zip。"""

    with _get_session_export_lock(session_id):
        return _build_session_export_zip_unlocked(session_id)


def build_session_export_archive(db: Session, session_id: int) -> Path:
    """在同一会话锁内刷新快照并创建本次请求专属 ZIP。"""

    with _get_session_export_lock(session_id):
        _export_session_snapshot_unlocked(db, session_id)
        return _build_session_export_zip_unlocked(session_id)


def _build_session_export_zip_unlocked(session_id: int) -> Path:
    """调用方持有会话导出锁时创建唯一 ZIP。"""

    source_dir = EXPORT_ROOT / f"convo_{session_id:04d}"
    if not source_dir.exists():
        raise ValueError("会话导出目录不存在，请先生成会话快照。")
    export_dir = STORAGE_DIR / "exports"
    export_dir.mkdir(parents=True, exist_ok=True)
    zip_path = _reserve_unique_zip_path(export_dir, session_id)
    try:
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for file_path in source_dir.rglob("*"):
                if file_path.is_file():
                    archive.write(file_path, file_path.relative_to(source_dir.parent))
    except BaseException:
        try:
            zip_path.unlink(missing_ok=True)
        except OSError:
            logger.warning("未能清理构建失败的唯一 ZIP：%s", zip_path, exc_info=True)
        raise
    return zip_path


def _reserve_unique_zip_path(export_dir: Path, session_id: int) -> Path:
    """排他创建并返回当前调用拥有的唯一 ZIP 路径。"""

    while True:
        path = export_dir / f"session_{session_id:04d}-{uuid.uuid4().hex}.zip"
        try:
            path.touch(exist_ok=False)
        except FileExistsError:
            continue
        return path
