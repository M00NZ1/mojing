"""Recoverable, derived progress for one Web memory-compaction batch."""

import hashlib
import json

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from ..models import AppSettingModel
from .memory_source_service import validate_compaction_sources


CHECKPOINT_VERSION = 1
MAX_MODEL_CALLS_PER_RUN = 4
CHECKPOINT_PREFIX = "web_memory_compact_v1:"


class CompactionPaused(Exception):
    """A valid checkpoint was saved and the bounded round can resume later."""


def checkpoint_key(session_id: int, branch_id: str) -> str:
    branch_hash = hashlib.sha256(branch_id.encode("utf-8")).hexdigest()[:24]
    return f"{CHECKPOINT_PREFIX}{session_id}:{branch_hash}"


def source_fingerprint(snapshot: tuple, previous_end: int, model: str) -> str:
    digest = hashlib.sha256()
    digest.update(f"{CHECKPOINT_VERSION}:{previous_end}:{model}\n".encode("utf-8"))
    for item in snapshot:
        encoded = json.dumps(item, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
    return digest.hexdigest()


def _valid_checkpoint(value: object, branch_id: str, fingerprint: str, source_ids: set[int]) -> bool:
    if not isinstance(value, dict) or value.get("version") != CHECKPOINT_VERSION:
        return False
    if value.get("branch_id") != branch_id or value.get("fingerprint") != fingerprint:
        return False
    if value.get("phase") not in ("summary", "events"):
        return False
    if type(value.get("next_chunk")) is not int or value["next_chunk"] < 0:
        return False
    if not isinstance(value.get("summary"), str) or not value["summary"] or len(value["summary"]) > 1000:
        return False
    if any(not isinstance(value.get(field), list) or len(value[field]) > 20 or
           any(not isinstance(item, str) or len(item) > width for item in value[field])
           for field, width in (("key_facts", 300), ("key_characters", 120))):
        return False
    if not isinstance(value.get("emotional_tone"), str) or len(value["emotional_tone"]) > 60:
        return False
    candidates = value.get("events")
    if not isinstance(candidates, list) or len(candidates) > 5:
        return False
    if any(not isinstance(candidate, list) or len(candidate) != 3 or
           type(candidate[0]) is not int or not 1 <= candidate[0] <= 5 or
           type(candidate[1]) is not int or candidate[1] < 0 or
           not isinstance(candidate[2], dict) or
           not isinstance(candidate[2].get("title"), str) or len(candidate[2]["title"]) > 200 or
           not isinstance(candidate[2].get("event_type"), str) or len(candidate[2]["event_type"]) > 60 or
           not isinstance(candidate[2].get("description"), str) or len(candidate[2]["description"]) > 2000 or
           not isinstance(candidate[2].get("parent_event_title"), str) or len(candidate[2]["parent_event_title"]) > 200 or
           type(candidate[2].get("message_id")) is not int or candidate[2]["message_id"] not in source_ids
           for candidate in candidates):
        return False
    if type(value.get("event_sequence")) is not int or value["event_sequence"] < 0:
        return False
    if value["phase"] == "summary" and (candidates or value["event_sequence"] != 0):
        return False
    return True


def read_checkpoint(db: Session, session_id: int, branch_id: str, fingerprint: str, source_ids: set[int]) -> dict | None:
    row = db.scalar(select(AppSettingModel).where(AppSettingModel.key == checkpoint_key(session_id, branch_id)))
    if row is None:
        return None
    if _valid_checkpoint(row.value_json, branch_id, fingerprint, source_ids):
        return row.value_json
    # This exact key is derived progress; an invalid snapshot must never become
    # a source of memory facts, even if the current batch is below threshold.
    db.delete(row)
    db.commit()
    return None


def save_checkpoint(
    db: Session,
    session_id: int,
    branch_id: str,
    fingerprint: str,
    snapshot: tuple,
    previous_end: int,
    state: dict,
) -> None:
    """Verify original sources under a short write lock before saving progress."""
    validate_compaction_sources(db, session_id, branch_id, snapshot, previous_end)
    key = checkpoint_key(session_id, branch_id)
    row = db.scalar(select(AppSettingModel).where(AppSettingModel.key == key))
    value = {"version": CHECKPOINT_VERSION, "branch_id": branch_id, "fingerprint": fingerprint, **state}
    if row is None:
        db.add(AppSettingModel(key=key, value_json=value))
    else:
        row.value_json = value
    db.commit()


def clear_checkpoint(db: Session, session_id: int, branch_id: str) -> None:
    db.execute(delete(AppSettingModel).where(AppSettingModel.key == checkpoint_key(session_id, branch_id)))


def clear_session_checkpoints(db: Session, session_id: int) -> None:
    prefix = f"{CHECKPOINT_PREFIX}{session_id}:"
    db.execute(delete(AppSettingModel).where(func.substr(AppSettingModel.key, 1, len(prefix)) == prefix))
