#!/usr/bin/env python3
"""
内容巡查辅助：列出后端 SQLite 中近期「用户」发言，供人工复核。

用法（在仓库根目录 `mojing/` 下）:
  python scripts/content_review_list_user_messages.py
  python scripts/content_review_list_user_messages.py --db backend/storage/app.db --limit 100

依赖：Python 3.10+ 标准库 sqlite3。
"""
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


def main() -> None:
    p = argparse.ArgumentParser(description="List recent user messages from app.db for manual review.")
    p.add_argument(
        "--db",
        type=Path,
        default=Path("backend/storage/app.db"),
        help="Path to SQLite database (default: backend/storage/app.db)",
    )
    p.add_argument("--limit", type=int, default=50, help="Max rows")
    args = p.parse_args()
    if not args.db.is_file():
        raise SystemExit(f"Database not found: {args.db.resolve()}")

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row
    cur = con.cursor()
    # messages: speaker_type 'user' for user-authored lines (schema may vary; tolerate missing columns)
    try:
        rows = cur.execute(
            """
            SELECT id, session_id, speaker_type, substr(content, 1, 500) AS preview, created_at
            FROM messages
            WHERE speaker_type = 'user'
            ORDER BY id DESC
            LIMIT ?
            """,
            (args.limit,),
        ).fetchall()
    except sqlite3.OperationalError as e:
        raise SystemExit(f"Query failed (check table name/columns): {e}") from e

    print(f"--- Recent user messages (limit {args.limit}) ---")
    for r in rows:
        print(dict(r))
    print("--- end ---")


if __name__ == "__main__":
    main()
