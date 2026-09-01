"""将仓库内 data/builtin_pack 中的内置媒体同步到 STORAGE_DIR，供 /storage 与资源中心 manifest 使用。"""

from __future__ import annotations

import shutil
from pathlib import Path

from ..config import PROJECT_DIR, STORAGE_DIR

_PACK_BUILTIN = PROJECT_DIR / "data" / "builtin_pack" / "assets" / "builtin"


def sync_builtin_media_pack() -> None:
    if not _PACK_BUILTIN.is_dir():
        return
    dest_root = STORAGE_DIR / "assets" / "builtin"
    for path in _PACK_BUILTIN.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(_PACK_BUILTIN)
        out = dest_root / rel
        try:
            out.parent.mkdir(parents=True, exist_ok=True)
            if not out.exists() or path.stat().st_mtime > out.stat().st_mtime:
                shutil.copy2(path, out)
        except OSError:
            continue
