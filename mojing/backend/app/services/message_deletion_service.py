"""Message deletion must not remove the source of a live storyline."""
from sqlalchemy import delete, func, select, text
from sqlalchemy.orm import Session

from ..models import MessageBookmarkModel, MessageModel, SessionBranchModel
from .memory_source_service import memory_deletion_plan, invalidate_deleted_source


def begin_storyline_write(db: Session) -> None:
    # Serialize source validation and mutation with other local branch writers.
    if db.get_bind().dialect.name == 'sqlite':
        connection = db.connection().connection.driver_connection
        if not connection.in_transaction:
            db.execute(text('BEGIN IMMEDIATE'))


def deletion_impact(db: Session, message: MessageModel) -> dict:
    references = select(SessionBranchModel).where(
        SessionBranchModel.session_id == message.session_id,
        SessionBranchModel.source_message_id == message.id,
    )
    count = db.scalar(select(func.count()).select_from(references.subquery())) or 0
    branches = list(db.scalars(references.order_by(SessionBranchModel.id).limit(10)))
    replacement = db.scalar(select(MessageModel.id).where(
        MessageModel.session_id == message.session_id,
        MessageModel.regenerated_from_message_id == message.id,
    ).limit(1))
    edited_version = message.regenerated_from_message_id is not None
    reason = ''
    if count or replacement is not None:
        reason = '这条消息是故事线或编辑版本的来源，删除会使相关剧情无法读取。请保留原文，使用编辑创建新的故事线。'
    elif edited_version:
        reason = '这条消息是编辑后的版本，删除会让旧版本重新出现。请使用编辑创建新的故事线。'
    memory_plan = memory_deletion_plan(db, message)
    return {
        'can_delete': count == 0 and replacement is None and not edited_version,
        'reference_count': count,
        'branches': [{'branch_id': row.branch_id, 'label': row.label or row.branch_id,
                      'is_checkpoint': bool(row.is_checkpoint)} for row in branches],
        'reason': reason,
        **{key: value for key, value in memory_plan.items() if key != 'start'},
    }


def remove_unreferenced_message(db: Session, message: MessageModel) -> dict:
    impact = deletion_impact(db, message)
    if not impact['can_delete']:
        return impact
    invalidate_deleted_source(db, message, memory_deletion_plan(db, message))
    # Some existing SQLite databases do not enable foreign_keys. Remove only
    # bookmarks for this message explicitly; attachment files remain untouched.
    db.execute(delete(MessageBookmarkModel).where(MessageBookmarkModel.message_id == message.id))
    db.delete(message)
    db.commit()
    return impact
