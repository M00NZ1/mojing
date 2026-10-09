from __future__ import annotations

import base64
import json
import struct
import zlib
from copy import deepcopy
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageOps


CHARA_KEYWORD = "chara"
V2_SPEC = "chara_card_v2"


def _build_tEXt_chunk(keyword: str, text: str) -> bytes:
    """构建 PNG tEXt 块。"""
    encoded = keyword.encode("latin-1") + b"\x00" + text.encode("latin-1")
    chunk_type = b"tEXt"
    length = struct.pack(">I", len(encoded))
    crc = struct.pack(">I", zlib.crc32(chunk_type + encoded) & 0xFFFFFFFF)
    return length + chunk_type + encoded + crc


def _replace_or_add_tEXt(png_data: bytes, keyword: str, text: str) -> bytes:
    """替换或添加 PNG 中的 tEXt chunk（按 keyword 匹配）。"""
    new_chunk = _build_tEXt_chunk(keyword, text)
    if not png_data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("图片不是有效的 PNG")
    result = bytearray(png_data[:8])
    offset = 8
    while offset + 12 <= len(png_data):
        length = struct.unpack(">I", png_data[offset : offset + 4])[0]
        actual_type = png_data[offset + 4 : offset + 8].decode("ascii", errors="replace")
        chunk_data = png_data[offset + 8 : offset + 8 + length]
        chunk_end = offset + 12 + length
        if chunk_end > len(png_data):
            raise ValueError("PNG 数据不完整")
        if actual_type == "IEND":
            result.extend(new_chunk)
            result.extend(png_data[offset:chunk_end])
            return bytes(result)
        if actual_type in ("tEXt", "zTXt", "iTXt"):
            null_idx = chunk_data.find(b"\x00")
            if null_idx > 0:
                kw = chunk_data[:null_idx].decode("latin-1", errors="replace")
                if kw == keyword:
                    offset = chunk_end
                    continue
        result.extend(png_data[offset:chunk_end])
        offset = chunk_end
    raise ValueError("PNG 缺少结束块")


def _decode_chara_b64(b64_text: str) -> dict | None:
    try:
        json_text = base64.b64decode(b64_text.strip()).decode("utf-8")
        data = json.loads(json_text)
        return data if isinstance(data, dict) else None
    except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def read_character_card_from_png_bytes(png_data: bytes) -> dict | None:
    """扫描 PNG 全部 tEXt / zTXt 块，查找关键字 chara（与 Android CharacterCardPngCodec 对齐）。"""
    if len(png_data) < 24 or png_data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    offset = 8
    while offset + 12 <= len(png_data):
        length = struct.unpack(">I", png_data[offset : offset + 4])[0]
        chunk_type = png_data[offset + 4 : offset + 8].decode("ascii", errors="replace")
        data_start = offset + 8
        data_end = data_start + length
        if data_end + 4 > len(png_data):
            break
        chunk_data = png_data[data_start:data_end]
        if chunk_type == "tEXt":
            null_idx = chunk_data.find(b"\x00")
            if null_idx > 0:
                keyword = chunk_data[:null_idx].decode("latin-1", errors="replace")
                if keyword == CHARA_KEYWORD:
                    b64_text = chunk_data[null_idx + 1 :].decode("latin-1", errors="replace")
                    hit = _decode_chara_b64(b64_text)
                    if hit:
                        return hit
        elif chunk_type == "zTXt":
            null_idx = chunk_data.find(b"\x00")
            if null_idx > 0 and null_idx + 2 <= len(chunk_data):
                keyword = chunk_data[:null_idx].decode("latin-1", errors="replace")
                if keyword == CHARA_KEYWORD and chunk_data[null_idx + 1] == 0:
                    compressed = chunk_data[null_idx + 2 :]
                    try:
                        inflated = zlib.decompress(compressed)
                        data = json.loads(inflated.decode("utf-8"))
                        if isinstance(data, dict):
                            return data
                    except (zlib.error, json.JSONDecodeError, UnicodeDecodeError):
                        pass
        offset = data_end + 4
    return None


def read_character_card_from_png(png_path: str | Path) -> dict | None:
    """从 PNG 文件读取角色卡 V2 数据。返回 None 表示未找到。"""
    png_data = Path(png_path).read_bytes()
    return read_character_card_from_png_bytes(png_data)


def write_character_card_to_png(input_png: str | Path, card_data: dict, output_png: str | Path):
    """将角色卡 V2 数据写入 PNG 文件。"""
    json_text = json.dumps(card_data, ensure_ascii=False)
    b64_text = base64.b64encode(json_text.encode("utf-8")).decode("latin-1")
    png_data = Path(input_png).read_bytes()
    result = _replace_or_add_tEXt(png_data, CHARA_KEYWORD, b64_text)
    Path(output_png).write_bytes(result)


def render_character_card_png(card_data: dict, image_path: Path | None = None) -> bytes:
    """生成独立下载内容，图片统一转为 PNG 后写入角色设定。"""
    buffer = BytesIO()
    if image_path is None:
        with Image.new("RGBA", (512, 512), (30, 41, 49, 255)) as image:
            image.save(buffer, format="PNG")
    else:
        with Image.open(image_path) as image:
            ImageOps.exif_transpose(image).convert("RGBA").save(buffer, format="PNG")
    encoded = base64.b64encode(json.dumps(card_data, ensure_ascii=False).encode("utf-8")).decode("ascii")
    return _replace_or_add_tEXt(buffer.getvalue(), CHARA_KEYWORD, encoded)


def convert_v2_to_internal(card_data: dict) -> dict:
    """将 V2 角色卡转换为项目内部人物数据格式。"""
    data = card_data.get("data", card_data)
    name = data.get("name", "")
    description = data.get("description", "")
    personality = data.get("personality", "")
    scenario = data.get("scenario", "")
    first_mes = data.get("first_mes", "")
    mes_example = data.get("mes_example", "")
    system_prompt = data.get("system_prompt", "")
    post_history = data.get("post_history_instructions", "")
    alternate_greetings = data.get("alternate_greetings", [])
    character_book = data.get("character_book")
    tags = data.get("tags", [])
    creator = data.get("creator", "")
    persona_parts = [description, personality]
    if scenario:
        persona_parts.insert(0, f"【场景】{scenario}")
    persona_prompt = "\n\n".join(part for part in persona_parts if part)
    if first_mes:
        persona_prompt += f"\n\n【开场白】\n{first_mes}"
    if mes_example:
        persona_prompt += f"\n\n【对话示例】\n{mes_example}"
    if system_prompt:
        persona_prompt += f"\n\n【系统提示】\n{system_prompt}"
    if post_history:
        persona_prompt += f"\n\n【后置指令】\n{post_history}"
    return {
        "name": name,
        "persona_prompt": persona_prompt.strip(),
        "system_prompt": system_prompt,
        "first_message": first_mes,
        "alternate_greetings": alternate_greetings,
        "character_book": character_book,
        "tags": tags,
        "creator": creator,
        "source_spec": V2_SPEC,
    }


def convert_internal_to_v2(character, profile=None) -> dict:
    """只读导出当前人设；未改的人设保留原卡结构，与 Android overlay 契约一致。"""
    raw = getattr(profile, "character_card_json", None)
    if isinstance(raw, dict) and isinstance(raw.get("tavern_chara_card_v2"), dict):
        raw = raw["tavern_chara_card_v2"]
    text_fields = ("description", "personality", "scenario", "first_mes", "mes_example",
                   "system_prompt", "post_history_instructions")
    original_root = None
    original_data = None
    if isinstance(raw, dict):
        if isinstance(raw.get("data"), dict) and (raw.get("spec") == V2_SPEC or "name" in raw["data"]):
            original_root = raw
            original_data = raw["data"]
        elif "name" in raw and any(key in raw for key in text_fields):
            original_data = raw
    data = deepcopy(original_data) if original_data is not None else {}
    original_persona = None
    if original_data is not None:
        # Old malformed structured fields must not break export or displace current text.
        try:
            original_persona = convert_v2_to_internal(original_data)["persona_prompt"]
        except (TypeError, ValueError, AttributeError):
            pass
    persona = character.persona_prompt or ""
    data["name"] = character.name
    if original_persona != persona:
        data["description"] = persona
        for key in text_fields[1:]:
            data[key] = ""
    defaults = {
        **{key: "" for key in text_fields},
        "alternate_greetings": [], "tags": [], "creator": "墨境", "creator_notes": "",
        "character_version": "", "extensions": {}, "character_book": None,
    }
    for key, value in defaults.items():
        data.setdefault(key, value)
    root = deepcopy(original_root) if original_root is not None else {}
    root.update(spec=V2_SPEC, spec_version="2.0", data=data)
    return root


def read_character_card_from_json(json_path: str | Path) -> dict | None:
    """从 JSON 文件读取角色卡数据。"""
    raw = Path(json_path).read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
        if "data" in data or "name" in data:
            return data
        return None
    except json.JSONDecodeError:
        return None
