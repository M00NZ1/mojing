from __future__ import annotations

import json
from pathlib import Path

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from ..models import (
    CharacterModel,
    ChatSessionModel,
    MessageModel,
    PromptTemplateModel,
    PromptTemplateRevisionModel,
    SessionWorldModel,
    VoiceProfileModel,
    WorldTemplateModel,
)
from .defaults import (
    BUILTIN_PROMPT_TEMPLATES,
    BUILTIN_VOICE_PRESETS,
    DEFAULT_WORLD_TEMPLATES,
    STARTER_CHARACTERS,
)
from .schema_migration_service import ensure_session_branch_schema
from .secret_migration_service import migrate_persisted_secrets


def _merge_starter_characters(db: Session) -> None:
    """旧版角色参考，仅由目录升级的隔离校验调用。"""
    existing = set(db.scalars(select(CharacterModel.name)).all())
    changed = False
    for spec in STARTER_CHARACTERS:
        name = str(spec["name"])
        if name in existing:
            continue
        db.add(
            CharacterModel(
                name=name,
                persona_prompt=str(spec["persona_prompt"]),
                temperature=float(spec["temperature"]),
                max_tokens=int(spec["max_tokens"]),
                avatar_color=str(spec["avatar_color"]),
            )
        )
        existing.add(name)
        changed = True
    if changed:
        db.commit()


def bootstrap_legacy_data(db: Session, project_dir: Path) -> None:
    """首次启动时把旧版配置和会话导入新数据库。"""

    _ensure_runtime_columns(db)
    migrate_persisted_secrets(db)
    _seed_builtin_voices(db)
    _seed_builtin_prompt_templates(db)
    _backfill_session_worlds(db)
    has_session = db.scalar(select(ChatSessionModel.id).limit(1))

    if has_session:
        return

    sessions_index_path = project_dir / "data" / "sessions_index.json"
    sessions_dir = project_dir / "data" / "sessions"
    if not sessions_index_path.exists() or not sessions_dir.exists():
        default_session = ChatSessionModel(title="新对话", summary="")
        db.add(default_session)
        db.commit()
        return

    raw_index = json.loads(sessions_index_path.read_text(encoding="utf-8"))
    for legacy_session in raw_index.get("sessions", []):
        session = ChatSessionModel(
            title=legacy_session.get("title", "历史对话"),
            summary="从旧桌面版自动导入。",
        )
        db.add(session)
        db.flush()
        db.add(SessionWorldModel(session_id=session.id, narrator_enabled=False, narrator_name="旁白"))

        session_path = sessions_dir / f"{legacy_session['id']}.json"
        if not session_path.exists():
            continue
        messages = json.loads(session_path.read_text(encoding="utf-8"))
        for item in messages:
            speaker_type = "user" if item.get("role") == "user" else "character"
            db.add(
                MessageModel(
                    session_id=session.id,
                    speaker_type=speaker_type,
                    content=item.get("content", ""),
                    structured_content={"raw": item.get("content", "")},
                )
            )

    db.commit()

    if not db.scalar(select(ChatSessionModel.id).limit(1)):
        session = ChatSessionModel(title="新对话", summary="")
        db.add(session)
        db.flush()
        db.add(SessionWorldModel(session_id=session.id, narrator_enabled=False, narrator_name="旁白"))
        db.commit()


def _seed_builtin_voices(db: Session) -> None:
    existing = {item.name for item in db.scalars(select(VoiceProfileModel))}
    changed = False
    for preset in BUILTIN_VOICE_PRESETS:
        if preset["name"] in existing:
            continue
        db.add(VoiceProfileModel(**preset))
        changed = True
    if changed:
        db.commit()


def _backfill_session_worlds(db: Session) -> None:
    existing_ids = {item.session_id for item in db.scalars(select(SessionWorldModel))}
    changed = False
    for session_id in db.scalars(select(ChatSessionModel.id)):
        if session_id in existing_ids:
            continue
        db.add(SessionWorldModel(session_id=int(session_id), narrator_enabled=False, narrator_name="旁白"))
        changed = True
    if changed:
        db.commit()


def _seed_builtin_world_templates(db: Session) -> None:
    existing = {
        item.template_id: item
        for item in db.scalars(select(WorldTemplateModel))
    }
    changed = False
    for preset in DEFAULT_WORLD_TEMPLATES:
        row = existing.get(preset["template_id"])
        if row is None:
            db.add(
                WorldTemplateModel(
                    template_id=preset["template_id"],
                    label=preset["label"],
                    category=preset["category"],
                    summary=preset["summary"],
                    gameplay_mode=preset["gameplay_mode"],
                    world_prompt=preset["world_prompt"],
                    suggested_choices_json=preset.get("suggested_choices", []),
                    anti_cheat_prompt=preset.get("anti_cheat_prompt", ""),
                    is_builtin=True,
                )
            )
            changed = True
            continue
        if row.is_builtin:
            row.label = preset["label"]
            row.category = preset["category"]
            row.summary = preset["summary"]
            row.gameplay_mode = preset["gameplay_mode"]
            row.world_prompt = preset["world_prompt"]
            row.suggested_choices_json = preset.get("suggested_choices", [])
            row.anti_cheat_prompt = preset.get("anti_cheat_prompt", "")
            changed = True
    if changed:
        db.commit()


def _seed_builtin_prompt_templates(db: Session) -> None:
    existing = {
        item.template_id: item
        for item in db.scalars(select(PromptTemplateModel))
    }
    existing_revisions = {
        (item.prompt_template_id, item.version): item
        for item in db.scalars(select(PromptTemplateRevisionModel))
    }
    changed = False
    for preset in BUILTIN_PROMPT_TEMPLATES:
        row = existing.get(preset["template_id"])
        if row is None:
            row = PromptTemplateModel(
                template_id=preset["template_id"],
                label=preset["label"],
                category=preset["category"],
                scope=preset["scope"],
                description=preset["description"],
                system_prompt=preset["system_prompt"],
                user_prompt=preset["user_prompt"],
                variables_json=preset["variables_json"],
                output_format=preset["output_format"],
                version=1,
                is_builtin=True,
            )
            db.add(row)
            db.flush()
            db.add(
                PromptTemplateRevisionModel(
                    prompt_template_id=row.id,
                    version=1,
                    system_prompt=row.system_prompt,
                    user_prompt=row.user_prompt,
                    variables_json=row.variables_json,
                    output_format=row.output_format,
                    change_note="内置模板初始化",
                )
            )
            changed = True
            continue
        if row.is_builtin:
            row.label = preset["label"]
            row.category = preset["category"]
            row.scope = preset["scope"]
            row.description = preset["description"]
            row.system_prompt = preset["system_prompt"]
            row.user_prompt = preset["user_prompt"]
            row.variables_json = preset["variables_json"]
            row.output_format = preset["output_format"]
            row.version = max(int(row.version or 1), 1)
            if (row.id, row.version) not in existing_revisions:
                db.add(
                    PromptTemplateRevisionModel(
                        prompt_template_id=row.id,
                        version=row.version,
                        system_prompt=row.system_prompt,
                        user_prompt=row.user_prompt,
                        variables_json=row.variables_json,
                        output_format=row.output_format,
                        change_note="补齐内置模板修订记录",
                    )
                )
            changed = True
    if changed:
        db.commit()


def _ensure_runtime_columns(db: Session) -> None:
    """在没有 Alembic 的前提下，为旧库补齐新字段。"""

    _ensure_columns(
        db,
        "characters",
        {
            "avatar_image_path": "ALTER TABLE characters ADD COLUMN avatar_image_path VARCHAR(500) DEFAULT ''",
        },
    )
    _ensure_columns(
        db,
        "session_worlds",
        {
            "encyclopedia_id": "ALTER TABLE session_worlds ADD COLUMN encyclopedia_id INTEGER",
            "template_id": "ALTER TABLE session_worlds ADD COLUMN template_id VARCHAR(80) DEFAULT 'custom'",
            "gameplay_mode": "ALTER TABLE session_worlds ADD COLUMN gameplay_mode VARCHAR(80) DEFAULT '自由剧情'",
            "choice_generation_enabled": "ALTER TABLE session_worlds ADD COLUMN choice_generation_enabled BOOLEAN DEFAULT 1",
            "max_choice_count": "ALTER TABLE session_worlds ADD COLUMN max_choice_count INTEGER DEFAULT 3",
            "suggested_choices_json": "ALTER TABLE session_worlds ADD COLUMN suggested_choices_json JSON DEFAULT '[]'",
            "anti_cheat_enabled": "ALTER TABLE session_worlds ADD COLUMN anti_cheat_enabled BOOLEAN DEFAULT 1",
            "anti_cheat_prompt": "ALTER TABLE session_worlds ADD COLUMN anti_cheat_prompt TEXT DEFAULT ''",
        },
    )
    _ensure_columns(
        db,
        "messages",
        {
            "branch_id": "ALTER TABLE messages ADD COLUMN branch_id VARCHAR(80) DEFAULT 'main'",
            "parent_message_id": "ALTER TABLE messages ADD COLUMN parent_message_id INTEGER",
            "regenerated_from_message_id": "ALTER TABLE messages ADD COLUMN regenerated_from_message_id INTEGER",
        },
    )
    _ensure_table(
        db,
        "session_branches",
        """
        CREATE TABLE session_branches (
            id INTEGER NOT NULL PRIMARY KEY,
            session_id INTEGER NOT NULL,
            branch_id VARCHAR(80) NOT NULL,
            label VARCHAR(120) DEFAULT '',
            source_message_id INTEGER NOT NULL,
            parent_branch_id VARCHAR(80) DEFAULT 'main',
            created_at DATETIME,
            is_checkpoint INTEGER NOT NULL DEFAULT 0,
            checkpoint_label VARCHAR(200) NOT NULL DEFAULT '',
            CONSTRAINT uq_session_branch UNIQUE (session_id, branch_id)
        )
        """,
    )
    ensure_session_branch_schema(db)
    _ensure_columns(
        db,
        "world_templates",
        {
            "cover_image_path": "ALTER TABLE world_templates ADD COLUMN cover_image_path VARCHAR(500) DEFAULT ''",
        },
    )
    _ensure_columns(
        db,
        "prompt_templates",
        {
            "version": "ALTER TABLE prompt_templates ADD COLUMN version INTEGER DEFAULT 1",
        },
    )


def _ensure_columns(db: Session, table_name: str, statements: dict[str, str]) -> None:
    existing_columns = {
        row[1]
        for row in db.execute(text(f"PRAGMA table_info({table_name})")).fetchall()
    }
    changed = False
    for column_name, statement in statements.items():
        if column_name in existing_columns:
            continue
        db.execute(text(statement))
        changed = True
    if changed:
        db.commit()


def _ensure_table(db: Session, table_name: str, create_sql: str) -> None:
    exists = db.execute(text("SELECT name FROM sqlite_master WHERE type='table' AND name=:name"), {"name": table_name}).fetchone()
    if exists:
        return
    db.execute(text(create_sql))
    db.commit()
