"""
SQLite 上模拟「单会话海量消息行 + 全表读出」的耗时与结果集大小（不依赖 FastAPI）。

用法（在 backend 目录）:
  python scripts/benchmark_sqlite_message_fetch.py

与后端 get_session_messages_page（分页）对比：若 API 只拉一页，单页耗时应稳定；
若客户端（Android）等价于「SELECT * 全表」，则随总行数近似线性恶化。
"""

from __future__ import annotations

import sqlite3
import tempfile
import time
from pathlib import Path


def main() -> None:
    path = Path(tempfile.mkdtemp()) / "stress.db"
    conn = sqlite3.connect(str(path))
    conn.execute(
        """
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            branch_id TEXT NOT NULL,
            content TEXT NOT NULL
        )
        """
    )
    conn.execute("CREATE INDEX idx_session ON messages(session_id, branch_id)")

    session_id = 1
    branch = "main"
    # 行数 × 每行字符：总量级接近「几十万 token 量级的一角」（按字估 token 的粗量级）
    rows = 8000
    chars_per_row = 6000
    chunk = "文" * chars_per_row

    t_ins = time.perf_counter()
    conn.executemany(
        "INSERT INTO messages(session_id, branch_id, content) VALUES (?,?,?)",
        [(session_id, branch, chunk) for _ in range(rows)],
    )
    conn.commit()
    t_ins_done = time.perf_counter()

    # 模拟 Android：一次读出该会话主分支全部消息
    t_all = time.perf_counter()
    cur = conn.execute(
        "SELECT id, content FROM messages WHERE session_id=? AND branch_id=? ORDER BY id ASC",
        (session_id, branch),
    )
    all_rows = cur.fetchall()
    t_all_done = time.perf_counter()

    total_chars = sum(len(r[1]) for r in all_rows)

    # 模拟 H5/后端分页：每页 80 条，倒序取再反转（简化）
    page_size = 80
    t_page = time.perf_counter()
    pages = 0
    last_id = None
    while True:
        if last_id is None:
            q = (
                "SELECT id, content FROM messages WHERE session_id=? AND branch_id=? "
                "ORDER BY id DESC LIMIT ?"
            )
            cur = conn.execute(q, (session_id, branch, page_size + 1))
        else:
            q = (
                "SELECT id, content FROM messages WHERE session_id=? AND branch_id=? AND id < ? "
                "ORDER BY id DESC LIMIT ?"
            )
            cur = conn.execute(q, (session_id, branch, last_id, page_size + 1))
        batch = cur.fetchall()
        if not batch:
            break
        pages += 1
        last_id = batch[-1][0]
        if len(batch) <= page_size:
            break
    t_page_done = time.perf_counter()

    mb = total_chars / 1024 / 1024 * 2  # UTF-16 粗算上界（Python str 实际为灵活表示，仅作量级）

    print(f"插入 {rows} 行 × ~{chars_per_row} 字: {(t_ins_done - t_ins):.3f}s")
    print(f"全表读出 {len(all_rows)} 行, 总字符约 {total_chars // 1_000_000}M: {(t_all_done - t_all):.3f}s")
    print(f"分页扫描轮数 ~{pages}（每页≤{page_size}）: {(t_page_done - t_page):.3f}s")
    print(f"粗估纯文本内存量级 ~{mb:.0f} MiB（若全部驻留 JS/Compose 状态，会再叠加对象开销）")
    conn.close()


if __name__ == "__main__":
    main()
