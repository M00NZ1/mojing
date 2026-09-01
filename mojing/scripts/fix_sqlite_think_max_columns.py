"""Dev helper: add columns missing from SQLite when Alembic history is out of sync."""
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / "backend" / "storage" / "app.db"


def _add_column(conn: sqlite3.Connection, table: str, column: str, ddl: str) -> None:
    cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
    if column not in cols:
        conn.execute(ddl)
        print(f"added {table}.{column}")
    else:
        print(f"skip {table}.{column}")


def main() -> None:
    conn = sqlite3.connect(DB)
    try:
        _add_column(
            conn,
            "characters",
            "think_max_enabled",
            "ALTER TABLE characters ADD COLUMN think_max_enabled INTEGER NOT NULL DEFAULT 0",
        )
        _add_column(
            conn,
            "characters",
            "think_max_model_name",
            "ALTER TABLE characters ADD COLUMN think_max_model_name TEXT NOT NULL DEFAULT ''",
        )
        _add_column(
            conn,
            "chat_sessions",
            "think_max_enabled",
            "ALTER TABLE chat_sessions ADD COLUMN think_max_enabled INTEGER NOT NULL DEFAULT 0",
        )
        _add_column(
            conn,
            "encyclopedia_entries",
            "cover_image_path",
            "ALTER TABLE encyclopedia_entries ADD COLUMN cover_image_path TEXT NOT NULL DEFAULT ''",
        )
        conn.commit()
    finally:
        conn.close()
    print("done")


if __name__ == "__main__":
    main()
