from __future__ import annotations

import os
import gc
import sys
import tempfile
from pathlib import Path


_TEST_RUNTIME = tempfile.TemporaryDirectory(prefix="mojing-pytest-")
_TEST_STORAGE = Path(_TEST_RUNTIME.name) / "storage"
_TEST_STORAGE.mkdir(parents=True, exist_ok=True)

# 必须先于任何 backend 模块导入，避免测试收集触碰项目 .env、正式 SQLite 或渠道文件。
os.environ["MOJING_DISABLE_ENV_FILE"] = "1"
os.environ["MOJING_STORAGE_DIR"] = str(_TEST_STORAGE)
os.environ["MOJING_DATABASE_URL"] = f"sqlite:///{(_TEST_STORAGE / 'app.db').as_posix()}"
os.environ["MOJING_CHANNEL_STORAGE_PATH"] = str(_TEST_STORAGE / "api_channels.json")


def pytest_sessionfinish(session, exitstatus) -> None:
    del session, exitstatus
    database_module = sys.modules.get("backend.app.database")
    if database_module is not None:
        database_module.engine.dispose()
    gc.collect()
    _TEST_RUNTIME.cleanup()
