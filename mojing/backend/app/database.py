from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import STORAGE_DIR, settings


STORAGE_DIR.mkdir(parents=True, exist_ok=True)


class Base(DeclarativeBase):
    """ORM 基类。"""


engine = create_engine(
    settings.database_url,
    connect_args={"check_same_thread": False},
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


def get_db() -> Generator[Session, None, None]:
    """FastAPI 依赖注入用数据库会话。"""

    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
