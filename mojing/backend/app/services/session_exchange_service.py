from __future__ import annotations

import io
import json
import zipfile
from pathlib import PurePosixPath

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..models import (
    CharacterModel,
    CharacterProfileModel,
    ChatSessionModel,
    MessageModel,
    SessionBranchModel,
    SessionCharacterStateModel,
    SessionMemoryCorrectionModel,
    SessionParticipantModel,
    SessionWorldModel,
    WorldTemplateModel,
)


MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
MAX_ENTRY_BYTES = 10 * 1024 * 1024
MAX_ENTRIES = 5000


class SessionExchangeError(ValueError):
    """用户可修复的会话交换包错误。"""


def import_session_archive(db: Session, archive_bytes: bytes) -> dict:
    """完整解析后，在一个事务中创建一个全新的本地会话。"""

    payload = _parse_archive(archive_bytes)
    try:
        if db.in_transaction():
            # FastAPI 的依赖通常尚未触发 autobegin；若调用方已有读事务，使用
            # savepoint 保证本次导入仍可整体回滚，而不吞掉调用方事务。
            with db.begin_nested():
                result = _import_payload(db, payload)
        else:
            with db.begin():
                result = _import_payload(db, payload)
    except SessionExchangeError:
        raise
    except (IntegrityError, TypeError, ValueError) as exc:
        raise SessionExchangeError("会话交换包的数据字段无效，导入已回滚") from exc
    return result


def _parse_archive(archive_bytes: bytes) -> dict:
    if not archive_bytes or len(archive_bytes) > MAX_ARCHIVE_BYTES:
        raise SessionExchangeError("ZIP 文件为空或超过 50 MB 大小上限")
    try:
        archive = zipfile.ZipFile(io.BytesIO(archive_bytes))
    except (OSError, zipfile.BadZipFile) as exc:
        raise SessionExchangeError("不是有效的 ZIP 会话包") from exc

    with archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise SessionExchangeError("ZIP 文件条目数量超过上限")
        files: dict[str, bytes] = {}
        total_size = 0
        for info in infos:
            name = _safe_zip_name(info.filename)
            if info.is_dir():
                continue
            if name in files:
                raise SessionExchangeError(f"ZIP 包含重复路径：{name}")
            if info.file_size > MAX_ENTRY_BYTES:
                raise SessionExchangeError(f"ZIP 条目过大：{name}")
            total_size += info.file_size
            if total_size > MAX_ARCHIVE_BYTES:
                raise SessionExchangeError("ZIP 解压后总大小超过 50 MB 上限")
            try:
                files[name] = archive.read(info)
            except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
                raise SessionExchangeError(f"无法读取 ZIP 条目：{name}") from exc

    root = _find_root(files)
    manifest = _read_json(files, root + "manifest.json", required=False)
    if manifest is not None:
        if not isinstance(manifest, dict) or manifest.get("format") != "mojing_session_exchange":
            raise SessionExchangeError("不支持的会话交换格式")
        if manifest.get("version") != 1:
            raise SessionExchangeError("不支持的会话交换版本")

    session = _read_json(files, root + "meta/session.json")
    participants = _read_json(files, root + "meta/participants.json")
    branches = _read_json(files, root + "meta/branches.json", required=False) or []
    corrections = _read_json(files, root + "meta/memory_corrections.json", required=False) or []
    world = _read_json(files, root + "meta/world.json", required=False) or {}
    messages = _read_messages(files, root + "messages/messages.jsonl")
    if not isinstance(session, dict) or not isinstance(participants, list):
        raise SessionExchangeError("会话元数据结构无效")
    if not isinstance(branches, list) or not isinstance(corrections, list) or not isinstance(world, dict):
        raise SessionExchangeError("会话附属数据结构无效")

    character_files = _read_character_files(files, root)
    _validate_payload(session, participants, branches, corrections, world, messages, character_files)
    return {
        "session": session,
        "participants": participants,
        "branches": branches,
        "corrections": corrections,
        "world": world,
        "messages": messages,
        "characters": character_files,
    }


def _safe_zip_name(name: str) -> str:
    if not name or "\\" in name:
        raise SessionExchangeError("ZIP 包含非法路径")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise SessionExchangeError("ZIP 包含越界路径")
    normalized = str(path)
    if normalized in ("", "."):
        raise SessionExchangeError("ZIP 包含非法路径")
    return normalized


def _find_root(files: dict[str, bytes]) -> str:
    if "manifest.json" in files:
        return ""
    roots = {name.split("/", 1)[0] for name in files if "/" in name}
    candidates = [root for root in roots if root.startswith("convo_") and f"{root}/meta/session.json" in files]
    if len(candidates) != 1:
        raise SessionExchangeError("找不到唯一的会话导出目录")
    return candidates[0] + "/"


def _read_json(files: dict[str, bytes], path: str, *, required: bool = True):
    raw = files.get(path)
    if raw is None:
        if required:
            raise SessionExchangeError(f"缺少必要文件：{path}")
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SessionExchangeError(f"JSON 文件无效：{path}") from exc


def _read_messages(files: dict[str, bytes], path: str) -> list[dict]:
    raw = files.get(path)
    if raw is None:
        raise SessionExchangeError(f"缺少必要文件：{path}")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SessionExchangeError("消息文件不是有效 UTF-8") from exc
    messages = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SessionExchangeError(f"消息 JSON 无效（第 {line_number} 行）") from exc
        messages.append(item)
    return messages


def _read_character_files(files: dict[str, bytes], root: str) -> dict[int, dict]:
    result = {}
    prefix = root + "characters/"
    for path, raw in files.items():
        if not (path.startswith(prefix) and path.endswith("/meta/character.json")):
            continue
        item = _read_json(files, path)
        if not isinstance(item, dict) or not isinstance(item.get("id"), int):
            raise SessionExchangeError(f"角色元数据无效：{path}")
        slug = path[len(prefix) :].split("/", 1)[0]
        item["_slug"] = slug
        profile_prefix = f"{prefix}{slug}/source/"
        raw_profiles = [(p, b) for p, b in files.items() if p.startswith(profile_prefix)]
        if raw_profiles:
            profile_path, profile_raw = sorted(raw_profiles)[0]
            try:
                item["raw_persona_text"] = profile_raw.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise SessionExchangeError(f"角色设定不是有效 UTF-8：{profile_path}") from exc
        item["profile_card"] = _read_json(files, f"{prefix}{slug}/memory/character_card.json", required=False) or {}
        item["profile_markdown"] = _read_text(files, f"{prefix}{slug}/memory/character_card.md", default="")
        item["dynamic_state"] = _read_json(files, f"{prefix}{slug}/memory/dynamic_state.json", required=False) or {}
        item["relations"] = _read_json(files, f"{prefix}{slug}/memory/relations.json", required=False) or {}
        item["private_facts"] = _read_jsonl(files, f"{prefix}{slug}/memory/private_memory.jsonl")
        item["event_log"] = _read_jsonl(files, f"{prefix}{slug}/memory/event_log.jsonl")
        if item["id"] in result:
            raise SessionExchangeError("角色 ID 重复")
        result[item["id"]] = item
    return result


def _read_text(files: dict[str, bytes], path: str, *, default: str) -> str:
    raw = files.get(path)
    if raw is None:
        return default
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SessionExchangeError(f"文本文件不是有效 UTF-8：{path}") from exc


def _read_jsonl(files: dict[str, bytes], path: str) -> list:
    raw = files.get(path)
    if raw is None:
        return []
    values = []
    for line_number, line in enumerate(_read_text(files, path, default="").splitlines(), 1):
        if line.strip():
            try:
                values.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise SessionExchangeError(f"JSONL 文件无效：{path}（第 {line_number} 行）") from exc
    return values


def _validate_payload(session, participants, branches, corrections, world, messages, characters) -> None:
    if not isinstance(session.get("title", ""), str):
        raise SessionExchangeError("会话标题无效")
    participant_ids = []
    for item in participants:
        if not isinstance(item, dict) or not isinstance(item.get("character_id"), int):
            raise SessionExchangeError("参与角色引用无效")
        if item["character_id"] in participant_ids:
            raise SessionExchangeError("参与角色重复")
        participant_ids.append(item["character_id"])
        if item["character_id"] not in characters:
            raise SessionExchangeError("缺少参与角色设定")
    message_ids = set()
    branch_ids = {"main"}
    for item in branches:
        if not isinstance(item, dict) or not isinstance(item.get("branch_id"), str) or not item["branch_id"]:
            raise SessionExchangeError("分支结构无效")
        if item["branch_id"] in branch_ids:
            raise SessionExchangeError("分支 ID 重复或覆盖 main")
        branch_ids.add(item["branch_id"])
    for item in messages:
        if not isinstance(item, dict) or not isinstance(item.get("id"), int):
            raise SessionExchangeError("消息结构无效")
        if item["id"] in message_ids:
            raise SessionExchangeError("消息 ID 重复")
        message_ids.add(item["id"])
        if not isinstance(item.get("branch_id", "main"), str) or item.get("branch_id", "main") not in branch_ids:
            raise SessionExchangeError("消息引用了不存在的分支")
    for item in branches:
        if item.get("source_message_id") not in message_ids:
            raise SessionExchangeError("分支 source_message_id 无效")
        if item.get("parent_branch_id", "main") not in branch_ids:
            raise SessionExchangeError("分支 parent_branch_id 无效")
    for item in corrections:
        if not isinstance(item, dict) or not isinstance(item.get("content"), str):
            raise SessionExchangeError("记忆纠正结构无效")


def _import_payload(db: Session, payload: dict) -> dict:
    source_session = payload["session"]
    session = ChatSessionModel(
        title=(source_session.get("title") or "导入会话")[:200],
        summary=source_session.get("summary") or "",
        think_max_enabled=bool(source_session.get("think_max_enabled", False)),
    )
    db.add(session)
    db.flush()

    character_map: dict[int, int] = {}
    for participant in sorted(payload["participants"], key=lambda item: item.get("sort_order", 0)):
        old_id = participant["character_id"]
        source = payload["characters"][old_id]
        name = _unique_character_name(db, source.get("name") or "导入角色")
        character = CharacterModel(
            name=name,
            persona_prompt=source.get("persona_prompt") or source.get("raw_persona_text") or "",
            api_key="",
            api_base_url="https://api.deepseek.com",
            model_name=source.get("model_name") or "deepseek-chat",
            temperature=source.get("temperature", 0.9),
            max_tokens=source.get("max_tokens", 1200),
            top_p=source.get("top_p", 1.0),
            top_k=source.get("top_k", 0),
            frequency_penalty=source.get("frequency_penalty", 0.0),
            presence_penalty=source.get("presence_penalty", 0.0),
            repetition_penalty=source.get("repetition_penalty", 1.0),
            avatar_color=source.get("avatar_color") or "#F97316",
            avatar_image_path="",
            card_image_path="",
            voice_profile_id=None,
            voice_provider="",
            voice_api_base_url="",
            voice_api_key="",
            voice_model="",
            image_gen_enabled=False,
            image_gen_api_key="",
            image_gen_base_url="",
            image_gen_model="dall-e-3",
        )
        db.add(character)
        db.flush()
        character_map[old_id] = character.id
        db.add(
            CharacterProfileModel(
                character_id=character.id,
                source_filename="imported_persona.txt",
                raw_persona_text=source.get("raw_persona_text") or source.get("persona_prompt") or "",
                character_card_json=source.get("profile_card") or {},
                character_card_markdown=source.get("profile_markdown") or "",
            )
        )
        db.add(SessionParticipantModel(
            session_id=session.id,
            character_id=character.id,
            sort_order=participant.get("sort_order", 0),
            talkativeness=participant.get("talkativeness", 0.7),
            muted=bool(participant.get("muted", False)),
            force_next=bool(participant.get("force_next", False)),
            allow_self_response=bool(participant.get("allow_self_response", False)),
            speaker_strategy=participant.get("speaker_strategy", "natural"),
        ))
        db.add(SessionCharacterStateModel(
            session_id=session.id,
            character_id=character.id,
            dynamic_state_json=source.get("dynamic_state") or {},
            relations_json=source.get("relations") or {},
            private_facts_json=source.get("private_facts") or [],
            event_log_json=source.get("event_log") or [],
        ))

    db.add(SessionWorldModel(session_id=session.id, **_world_values(db, payload["world"])))
    db.flush()

    message_map: dict[int, int] = {}
    message_rows = []
    old_character_by_name = {
        source.get("name"): old_id for old_id, source in payload["characters"].items() if source.get("name")
    }
    for item in payload["messages"]:
        character_id = item.get("character_id")
        if not isinstance(character_id, int):
            character_id = old_character_by_name.get(item.get("character_name"))
        row = MessageModel(
            session_id=session.id,
            speaker_type=item.get("speaker_type", "user"),
            character_id=character_map.get(character_id),
            branch_id=item.get("branch_id", "main"),
            content=item.get("content") or "",
            structured_content=item.get("structured_content") or {},
            swipe_group_id=item.get("swipe_group_id"),
            include_in_context=bool(item.get("include_in_context", True)),
        )
        db.add(row)
        db.flush()
        message_map[item["id"]] = row.id
        message_rows.append((item, row))
    for item, row in message_rows:
        row.parent_message_id = message_map.get(item.get("parent_message_id"))
        row.regenerated_from_message_id = message_map.get(item.get("regenerated_from_message_id"))

    for item in payload["branches"]:
        db.add(SessionBranchModel(
            session_id=session.id,
            branch_id=item["branch_id"],
            label=item.get("label") or "",
            source_message_id=message_map[item["source_message_id"]],
            parent_branch_id=item.get("parent_branch_id", "main"),
            is_checkpoint=bool(item.get("is_checkpoint", False)),
            checkpoint_label=item.get("checkpoint_label") or "",
        ))
    for item in payload["corrections"]:
        branch_id = item.get("branch_id") if item.get("branch_id") in {"main", *[b["branch_id"] for b in payload["branches"]]} else None
        db.add(SessionMemoryCorrectionModel(
            session_id=session.id,
            branch_id=branch_id,
            content=item["content"],
            source_message_id=message_map.get(item.get("source_message_id")),
        ))
    db.flush()
    return {
        "session_id": session.id,
        "title": session.title,
        "message_count": len(message_rows),
        "character_count": len(character_map),
        "branch_count": len(payload["branches"]),
    }


def _unique_character_name(db: Session, base: str) -> str:
    base = base[:120] or "导入角色"
    candidate = base
    index = 1
    while db.scalar(select(CharacterModel.id).where(CharacterModel.name == candidate)) is not None:
        index += 1
        suffix = "（导入）" if index == 2 else f"（导入{index}）"
        candidate = f"{base[:120 - len(suffix)]}{suffix}"
    return candidate


def _world_values(db: Session, source: dict) -> dict:
    template_id = source.get("template_id") or "custom"
    if template_id != "custom" and db.scalar(select(WorldTemplateModel.id).where(WorldTemplateModel.template_id == template_id)) is None:
        template_id = "custom"
    return {
        "template_id": template_id,
        "world_prompt": source.get("world_prompt") or "",
        "gameplay_mode": source.get("gameplay_mode") or "自由剧情",
        "narrator_enabled": bool(source.get("narrator_enabled", False)),
        "narrator_name": source.get("narrator_name") or "旁白",
        "choice_generation_enabled": bool(source.get("choice_generation_enabled", True)),
        "max_choice_count": source.get("max_choice_count", 3),
        "suggested_choices_json": source.get("suggested_choices") or [],
        "anti_cheat_enabled": bool(source.get("anti_cheat_enabled", True)),
        "anti_cheat_prompt": source.get("anti_cheat_prompt") or "",
        "world_timeline_json": source.get("world_timeline") or [],
        "auto_sediment_enabled": bool(source.get("auto_sediment_enabled", True)),
        "sediment_interval": source.get("sediment_interval", 20),
    }
