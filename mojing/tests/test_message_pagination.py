from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.models import ChatSessionModel, MessageModel
from backend.app.services.chat_service import (
    get_session_message_window,
    get_session_messages_after_page,
    get_session_messages_page,
)


def test_cursor_pagination_returns_every_message_once(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'messages.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)

    try:
        with Session() as db:
            chat_session = ChatSessionModel(title="分页契约")
            db.add(chat_session)
            db.flush()
            db.add_all(
                MessageModel(session_id=chat_session.id, branch_id="main", content=f"消息 {index}")
                for index in range(1, 86)
            )
            db.commit()

            pages: list[list[int]] = []
            cursor = None
            while True:
                rows, cursor = get_session_messages_page(db, chat_session.id, cursor, 40)
                pages.insert(0, [row.id for row in rows])
                if cursor is None:
                    break

        message_ids = [message_id for page in pages for message_id in page]
        assert message_ids == list(range(1, 86))
        assert len(message_ids) == len(set(message_ids))
    finally:
        engine.dispose()


def test_message_window_supports_bounded_bidirectional_keyset_navigation(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'message-window.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)

    try:
        with Session() as db:
            chat_session = ChatSessionModel(title="搜索定位契约")
            db.add(chat_session)
            db.flush()
            db.add_all(
                MessageModel(session_id=chat_session.id, branch_id="main", content=f"消息 {index}")
                for index in range(1, 126)
            )
            db.commit()

            rows, older_cursor, newer_cursor = get_session_message_window(
                db,
                chat_session.id,
                anchor_id=53,
                radius=5,
            )
            assert [row.id for row in rows] == list(range(48, 59))
            assert older_cursor == 48
            assert newer_cursor == 58

            older, next_older = get_session_messages_page(db, chat_session.id, older_cursor, 40)
            assert [row.id for row in older] == list(range(8, 48))
            assert next_older == 8

            newer, next_newer = get_session_messages_after_page(db, chat_session.id, newer_cursor, 40)
            assert [row.id for row in newer] == list(range(59, 99))
            assert next_newer == 98
            newest, final_cursor = get_session_messages_after_page(db, chat_session.id, next_newer, 40)
            assert [row.id for row in newest] == list(range(99, 126))
            assert final_cursor is None

            missing, missing_older, missing_newer = get_session_message_window(
                db,
                chat_session.id,
                anchor_id=999_999,
                radius=5,
            )
            assert missing == []
            assert missing_older is None
            assert missing_newer is None
    finally:
        engine.dispose()
