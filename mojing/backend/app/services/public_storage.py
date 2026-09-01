from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles


PUBLIC_STORAGE_DIRECTORIES = (
    "assets",
    "audio",
    "avatars",
    "expressions",
    "generated",
    "uploads",
    "voices",
)


def mount_public_storage(app: FastAPI, storage_dir: Path) -> None:
    """只挂载用户界面需要读取的媒体目录，禁止暴露数据库、密钥和备份。"""

    for directory_name in PUBLIC_STORAGE_DIRECTORIES:
        directory = storage_dir / directory_name
        directory.mkdir(parents=True, exist_ok=True)
        app.mount(
            f"/storage/{directory_name}",
            StaticFiles(directory=directory, follow_symlink=False),
            name=f"storage_{directory_name}",
        )
