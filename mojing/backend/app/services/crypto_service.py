from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from ..config import settings, STORAGE_DIR

_fernet_instance: Fernet | None = None
_fernet_key_marker = ""
SECRET_MASK = "••••••••(已保存)"
_LEGACY_SECRET_MASK = "••••••••(已加密)"


class SecretStorageError(RuntimeError):
    """本机密钥不可用或已保存密文损坏。"""


class SecretKeyUnavailableError(SecretStorageError):
    """存在密文，但配置的本机 Fernet 密钥不可用。"""


class SecretDecryptionError(SecretStorageError):
    """密文无法由当前本机密钥解密。"""


def _read_or_create_key(*, create_if_missing: bool) -> str:
    key = settings.encryption_key.strip()
    if key:
        return key

    key_file = STORAGE_DIR / ".fernet_key"
    marker_file = STORAGE_DIR / ".fernet_key.id"
    if key_file.exists():
        key = key_file.read_text(encoding="utf-8").strip()
        if key:
            _ensure_key_marker(marker_file, key)
            return key
        raise SecretKeyUnavailableError("本机密钥文件为空，已停止读取访问密钥。")
    if not create_if_missing:
        raise SecretKeyUnavailableError("本机加密密钥缺失，已停止读取已保存的访问密钥。")
    if marker_file.exists():
        raise SecretKeyUnavailableError("检测到本机曾保存加密密钥，但密钥文件已缺失；禁止生成替代密钥。")

    STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    generated = Fernet.generate_key()
    try:
        descriptor = os.open(key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    except FileExistsError:
        key = key_file.read_text(encoding="utf-8").strip()
        if not key:
            raise SecretKeyUnavailableError("本机密钥文件为空，已停止保存访问密钥。")
        return key
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(generated)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            key_file.unlink()
        except OSError:
            pass
        raise
    generated_text = generated.decode("ascii")
    try:
        _ensure_key_marker(marker_file, generated_text)
    except Exception:
        try:
            key_file.unlink()
        except OSError:
            pass
        raise
    os.chmod(key_file, 0o600)
    return generated_text


def _get_fernet(*, create_if_missing: bool) -> Fernet:
    global _fernet_instance, _fernet_key_marker
    if _fernet_instance is not None:
        return _fernet_instance
    key = _read_or_create_key(create_if_missing=create_if_missing)
    marker = hashlib.sha256(key.encode("utf-8")).hexdigest()
    if _fernet_instance is not None and _fernet_key_marker == marker:
        return _fernet_instance

    try:
        _fernet_instance = Fernet(key.encode("utf-8"))
    except ValueError:
        # 如果 key 不是合法的 fernet key (比如只是普通字符串)，则用 kdf 转换一下
        # Keep the pre-rename KDF salt byte-for-byte stable so existing local
        # encrypted values remain readable. The value is expressed as hex to
        # avoid treating the former product identifier as current branding.
        stable_legacy_salt = bytes.fromhex("646565707365656b5f70726f5f73616c74")
        kdf = PBKDF2HMAC(
            algorithm=hashes.SHA256(),
            length=32,
            salt=stable_legacy_salt,
            iterations=100000,
        )
        derived_key = base64.urlsafe_b64encode(kdf.derive(key.encode("utf-8")))
        _fernet_instance = Fernet(derived_key)

    _fernet_key_marker = marker
    return _fernet_instance


def _ensure_key_marker(marker_file: Path, key: str) -> None:
    expected = hashlib.sha256(key.encode("utf-8")).hexdigest()
    if marker_file.exists():
        current = marker_file.read_text(encoding="ascii").strip()
        if current != expected:
            raise SecretKeyUnavailableError("本机密钥标识与密钥文件不匹配，已停止使用访问密钥。")
        return
    try:
        descriptor = os.open(marker_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    except FileExistsError:
        current = marker_file.read_text(encoding="ascii").strip()
        if current != expected:
            raise SecretKeyUnavailableError("本机密钥标识与密钥文件不匹配，已停止使用访问密钥。")
        return
    with os.fdopen(descriptor, "w", encoding="ascii", newline="\n") as handle:
        handle.write(expected + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(marker_file, 0o600)


def encrypt_api_key(raw_key: str) -> str:
    """加密 API Key。"""
    if not raw_key:
        return ""
    if raw_key.startswith("enc_v1:"):
        return raw_key  # 已经加密过
    f = _get_fernet(create_if_missing=True)
    encrypted = f.encrypt(raw_key.encode("utf-8")).decode("utf-8")
    return f"enc_v1:{encrypted}"


def decrypt_api_key(encrypted_key: str) -> str:
    """解密访问密钥；旧明文原样兼容，密钥缺失或密文损坏时明确失败。"""
    if not encrypted_key:
        return ""
    if not encrypted_key.startswith("enc_v1:"):
        return encrypted_key  # 假定是之前存留的明文
    f = _get_fernet(create_if_missing=False)
    token = encrypted_key[7:]
    try:
        decrypted = f.decrypt(token.encode("utf-8")).decode("utf-8")
        return decrypted
    except (InvalidToken, UnicodeDecodeError, ValueError) as exc:
        raise SecretDecryptionError("已保存的访问密钥无法由当前本机密钥解密。") from exc


def is_encrypted_secret(value: str | None) -> bool:
    return bool(value and value.startswith("enc_v1:"))


def is_secret_mask(value: str | None) -> bool:
    return value in {SECRET_MASK, _LEGACY_SECRET_MASK}


def mask_secret(value: str | None) -> str:
    return SECRET_MASK if value else ""


def prepare_secret_for_storage(value: str | None, *, existing: str = "") -> str:
    """把 API patch 中的明文加密；掩码表示保留已保存值，空串表示主动清空。"""

    normalized = value or ""
    if is_secret_mask(normalized):
        if not existing:
            raise SecretStorageError("密钥掩码不能作为新的访问密钥保存。")
        return existing
    return encrypt_api_key(normalized)


def _reset_fernet_cache_for_tests() -> None:
    global _fernet_instance, _fernet_key_marker
    _fernet_instance = None
    _fernet_key_marker = ""
