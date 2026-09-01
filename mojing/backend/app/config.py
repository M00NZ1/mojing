import os
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


BASE_DIR = Path(__file__).resolve().parents[1]
PROJECT_DIR = BASE_DIR.parent
STORAGE_DIR = Path(os.environ.get("MOJING_STORAGE_DIR", str(BASE_DIR / "storage"))).resolve()
LOCAL_CORS_ORIGIN_REGEX = r"^https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$"
SETTINGS_ENV_FILE = None if os.environ.get("MOJING_DISABLE_ENV_FILE") == "1" else PROJECT_DIR / ".env"


def resolve_project_config_path() -> Path:
    """返回本机 JSON 配置路径。该文件始终被 Git 忽略。"""
    return PROJECT_DIR / "mojing_config.json"


class Settings(BaseSettings):
    """后端运行配置。"""

    model_config = SettingsConfigDict(
        env_prefix="MOJING_",
        env_file=SETTINGS_ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "墨境"
    api_prefix: str = "/api"
    database_url: str = f"sqlite:///{(STORAGE_DIR / 'app.db').as_posix()}"
    cors_origins: list[str] = Field(default_factory=list)
    cors_origin_regex: str = LOCAL_CORS_ORIGIN_REGEX
    narration_voice: str = "zh-CN-XiaoxiaoNeural"
    default_model: str = "deepseek-chat"
    recent_message_limit: int = 24
    encryption_key: str = ""  # 用于加密 API Key (Fernet 密钥，需 32 字节 url-safe base64)
    channel_storage_path: str = ""  # 渠道存储路径


settings = Settings()
