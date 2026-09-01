"""结构化日志配置。

用法:
    from app.logger import logger
    logger.info("服务启动", extra={"host": "0.0.0.0", "port": 8000})

输出的 JSON 格式:
    {"ts":"...", "level":"INFO", "logger":"mojing", "msg":"...", "host":"0.0.0.0", "port":8000}
"""

import json
import logging
import sys
from datetime import datetime, timezone

# LogRecord 的标准属性名（不放入 extra 字段）
_STANDARD_ATTRS = {
    "args", "asctime", "created", "exc_info", "exc_text", "filename",
    "funcName", "levelname", "levelno", "lineno", "message", "module",
    "msecs", "msg", "name", "pathname", "process", "processName",
    "relativeCreated", "stack_info", "taskName", "thread", "threadName",
}


class StructuredFormatter(logging.Formatter):
    """输出 JSON 格式的结构化日志。"""

    def format(self, record: logging.LogRecord) -> str:
        log_entry = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key, val in record.__dict__.items():
            if key not in _STANDARD_ATTRS and not key.startswith("_"):
                log_entry[key] = val
        if record.exc_info and record.exc_info[0]:
            log_entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(log_entry, ensure_ascii=False)


def _ensure_utf8_stdio() -> None:
    """Windows 等环境下控制台默认编码可能是 cp1252，输出中文 JSON 会触发 UnicodeEncodeError，导致进程启动即崩。"""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError, AttributeError, TypeError):
                pass


def setup_logging(level: str = "INFO") -> None:
    """配置全局日志：JSON 格式输出到控制台。"""
    _ensure_utf8_stdio()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(StructuredFormatter())
    root = logging.getLogger()
    root.setLevel(getattr(logging, level.upper(), logging.INFO))
    root.handlers.clear()
    root.addHandler(handler)


logger = logging.getLogger("mojing")
