"""Keep derived memory tied to the original messages, without rewriting user facts."""
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from ..models import ChatSessionModel, MessageModel, SessionEventNodeModel, SessionMemorySegmentModel


def memory_invalidation_plan(db: Session, message: MessageModel) -> dict:
    scope = (
        SessionMemorySegmentModel.session_id == message.session_id,
        SessionMemorySegmentModel.branch_id == message.branch_id,
        SessionMemorySegmentModel.end_message_id >= message.id,
    )
    count, first = db.execute(select(func.count(), func.min(SessionMemorySegmentModel.start_message_id)).where(*scope)).one()
    # Rewind from the first affected segment, so the existing cursor can rebuild
    # the tail rather than permanently skipping a hole in the middle.
    start = min(message.id, first) if first and first > 0 else message.id
    events = db.scalar(select(func.count()).select_from(SessionEventNodeModel).where(
        SessionEventNodeModel.session_id == message.session_id,
        SessionEventNodeModel.branch_id == message.branch_id,
        SessionEventNodeModel.message_id >= start,
    )) or 0
    session = db.get(ChatSessionModel, message.session_id)
    return {'start': start, 'memory_segments_removed': count, 'memory_events_removed': events,
            'summary_reset': message.branch_id == 'main' and bool(session and session.summary)}


def invalidate_source_memory(db: Session, message: MessageModel, plan: dict) -> None:
    """Use the caller's original-message transaction; never commit independently."""
    db.execute(delete(SessionMemorySegmentModel).where(
        SessionMemorySegmentModel.session_id == message.session_id,
        SessionMemorySegmentModel.branch_id == message.branch_id,
        SessionMemorySegmentModel.end_message_id >= message.id,
    ))
    db.execute(delete(SessionEventNodeModel).where(
        SessionEventNodeModel.session_id == message.session_id,
        SessionEventNodeModel.branch_id == message.branch_id,
        SessionEventNodeModel.message_id >= plan['start'],
    ))
    if plan['summary_reset']:
        db.get(ChatSessionModel, message.session_id).summary = ''


def source_snapshot(messages) -> tuple:
    return tuple((m.id, m.content, m.speaker_type, m.character_id) for m in messages)


class MemorySourceChanged(RuntimeError):
    pass


def validate_compaction_sources(db: Session, session_id: int, branch_id: str, snapshot: tuple, previous_end: int) -> None:
    """Take a short write lock after remote work, then compare a bounded source page.

    Pending derived rows must not flush before validation. Message writers use
    the same SQLite lock, so a delete either invalidates this result afterwards
    or makes this check reject it before it can be saved.
    """
    from .message_deletion_service import begin_storyline_write

    with db.no_autoflush:
        begin_storyline_write(db)
        current = tuple(tuple(row) for row in db.execute(select(
            MessageModel.id, MessageModel.content, MessageModel.speaker_type, MessageModel.character_id,
        ).where(
            MessageModel.session_id == session_id,
            MessageModel.branch_id == branch_id,
            MessageModel.include_in_context == True,
            MessageModel.id > previous_end,
            MessageModel.id <= snapshot[-1][0],
        ).order_by(MessageModel.id).limit(len(snapshot) + 1)))
        current_end = db.scalar(select(func.max(SessionMemorySegmentModel.end_message_id)).where(
            SessionMemorySegmentModel.session_id == session_id,
            SessionMemorySegmentModel.branch_id == branch_id,
        )) or 0
        if current != snapshot or current_end != previous_end:
            raise MemorySourceChanged('Original history changed while memory was being generated')
