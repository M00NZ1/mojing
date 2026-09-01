"""
对象存储抽象层：本地存储 / MinIO / S3 兼容。
"""
from __future__ import annotations

import shutil
from pathlib import Path
from typing import IO

from ..config import STORAGE_DIR, settings


class LocalStorageBackend:
    """本地文件系统后端。"""

    def __init__(self, base_dir: Path):
        self._base = base_dir

    def put(self, key: str, data: bytes | IO, content_type: str = "application/octet-stream") -> str:
        dest = self._base / key
        dest.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, bytes):
            dest.write_bytes(data)
        else:
            with dest.open("wb") as f:
                shutil.copyfileobj(data, f)
        return key

    def get(self, key: str) -> bytes | None:
        path = self._base / key
        if not path.is_relative_to(self._base) or not path.exists():
            return None
        return path.read_bytes()

    def url(self, key: str) -> str:
        return f"/storage/{key}"

    def delete(self, key: str) -> bool:
        path = self._base / key
        if path.is_relative_to(self._base) and path.exists():
            path.unlink()
            return True
        return False


class S3StorageBackend:
    """S3 / MinIO 兼容后端（需要 boto3）。"""

    def __init__(self, endpoint: str, bucket: str, access_key: str, secret_key: str, region: str = "us-east-1"):
        self._endpoint = endpoint
        self._bucket = bucket
        try:
            import boto3
            self._client = boto3.client(
                "s3",
                endpoint_url=endpoint,
                aws_access_key_id=access_key,
                aws_secret_access_key=secret_key,
                region_name=region,
            )
        except ImportError:
            self._client = None

    def _ensure_client(self):
        if self._client is None:
            raise RuntimeError("boto3 未安装，无法使用 S3 后端。请安装: pip install boto3")

    def put(self, key: str, data: bytes | IO, content_type: str = "application/octet-stream") -> str:
        self._ensure_client()
        if isinstance(data, bytes):
            self._client.put_object(Bucket=self._bucket, Key=key, Body=data, ContentType=content_type)
        else:
            self._client.upload_fileobj(data, self._bucket, key, ExtraArgs={"ContentType": content_type})
        return key

    def get(self, key: str) -> bytes | None:
        self._ensure_client()
        try:
            obj = self._client.get_object(Bucket=self._bucket, Key=key)
            return obj["Body"].read()
        except self._client.exceptions.NoSuchKey:
            return None

    def url(self, key: str) -> str:
        return f"{self._endpoint}/{self._bucket}/{key}"

    def delete(self, key: str) -> bool:
        self._ensure_client()
        self._client.delete_object(Bucket=self._bucket, Key=key)
        return True


_storage: LocalStorageBackend | S3StorageBackend | None = None


def get_storage() -> LocalStorageBackend | S3StorageBackend:
    global _storage
    if _storage is not None:
        return _storage
    s3_endpoint = getattr(settings, "s3_endpoint", "") or ""
    if s3_endpoint:
        _storage = S3StorageBackend(
            endpoint=s3_endpoint,
            bucket=getattr(settings, "s3_bucket", "mojing"),
            access_key=getattr(settings, "s3_access_key", ""),
            secret_key=getattr(settings, "s3_secret_key", ""),
        )
    else:
        _storage = LocalStorageBackend(STORAGE_DIR)
    return _storage
