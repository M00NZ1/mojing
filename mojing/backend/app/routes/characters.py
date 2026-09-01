import json
import shutil
from copy import deepcopy
from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.orm import Session, joinedload

from ..config import STORAGE_DIR
from ..database import get_db
from ..models import CharacterModel, CharacterProfileModel
from ..schemas import (
    CharacterCardImageGenBody,
    CharacterCardImagePreviewBody,
    CharacterCardImagePreviewResult,
    CharacterCreate,
    CharacterProfileImportRequest,
    CharacterProfileRead,
    CharacterRead,
    CharacterTemplate,
    CharacterUpdate,
    VoiceBindingRequest)
from ..services.defaults import DEFAULT_CHARACTER_TEMPLATES
from ..services.memory_service import DEFAULT_CARD, import_character_profile
from ..services.crypto_service import mask_secret, prepare_secret_for_storage
from ..services.character_card_service import (
    convert_internal_to_v2,
    convert_v2_to_internal,
    read_character_card_from_png_bytes,
    write_character_card_to_png)
from ..services.character_portable_service import (
    PORTABLE_KIND,
    PORTABLE_VERSION,
    allocate_unique_character_name,
    build_portable_payload,
    collect_character_dump_for_summary,
    portable_from_txt,
    portable_json_dumps,
    portable_to_txt,
    summarize_portable_with_public_llm,
)
from ..utils.docx_simple import write_plain_docx



router = APIRouter(prefix="/characters", tags=["人物"])


def _character_read(character: CharacterModel) -> CharacterRead:
    value = CharacterRead.model_validate(character)
    return value.model_copy(
        update={
            "api_key": mask_secret(character.api_key),
            "voice_api_key": mask_secret(character.voice_api_key),
            "image_gen_api_key": mask_secret(character.image_gen_api_key),
        }
    )


@router.get("", summary="获取角色列表", response_model=list[CharacterRead])
def list_characters(db: Session = Depends(get_db)):
    characters = list(
        db.scalars(
            select(CharacterModel)
            .options(joinedload(CharacterModel.voice_profile))
            .order_by(CharacterModel.favorite.desc(), CharacterModel.id.desc())
        )
    )
    return [_character_read(character) for character in characters]


@router.post("", summary="创建角色", response_model=CharacterRead)
def create_character(payload: CharacterCreate, db: Session = Depends(get_db)):
    data = payload.model_dump()
    for field in ("api_key", "voice_api_key", "image_gen_api_key"):
        data[field] = prepare_secret_for_storage(str(data.get(field) or ""))
    character = CharacterModel(**data)
    db.add(character)
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.get("/templates/defaults", summary="获取默认模板", response_model=list[CharacterTemplate])
def list_default_templates():
    return DEFAULT_CHARACTER_TEMPLATES


@router.put("/{character_id}", summary="更新角色", response_model=CharacterRead)
def update_character(character_id: int, payload: CharacterUpdate, db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")

    data = payload.model_dump(exclude_unset=True)
    clear_fields = {
        "api_key": bool(data.pop("clear_api_key", False)),
        "voice_api_key": bool(data.pop("clear_voice_api_key", False)),
        "image_gen_api_key": bool(data.pop("clear_image_gen_api_key", False)),
    }
    for field in ("api_key", "voice_api_key", "image_gen_api_key"):
        if clear_fields[field]:
            data[field] = ""
        elif field not in data or not str(data.get(field) or ""):
            data.pop(field, None)
        else:
            data[field] = prepare_secret_for_storage(
                str(data[field]),
                existing=str(getattr(character, field) or ""),
            )

    for field, value in data.items():
        setattr(character, field, value)
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.get("/{character_id}", summary="获取角色详情", response_model=CharacterRead)
def get_character(character_id: int, db: Session = Depends(get_db)):
    char = db.get(CharacterModel, character_id)
    if char is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    return _character_read(char)


@router.post("/{character_id}/bind-voice", summary="绑定声色", response_model=CharacterRead)
def bind_voice(character_id: int, payload: VoiceBindingRequest, db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    character.voice_profile_id = payload.voice_profile_id
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.post("/{character_id}/avatar", summary="上传头像", response_model=CharacterRead)
def upload_character_avatar(character_id: int, file: UploadFile = File(...), db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    if not (file.content_type or "").startswith("image/"):
        raise HTTPException(status_code=400, detail="头像只支持图片文件")

    avatar_dir = STORAGE_DIR / "avatars" / "characters"
    avatar_dir.mkdir(parents=True, exist_ok=True)
    suffix = Path(file.filename or "avatar.png").suffix or ".png"
    target = avatar_dir / f"character_{character_id:04d}{suffix.lower()}"
    with target.open("wb") as output:
        shutil.copyfileobj(file.file, output)

    character.avatar_image_path = str(target.relative_to(STORAGE_DIR)).replace("\\", "/")
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.get("/{character_id}/profile", summary="获取人物卡", response_model=CharacterProfileRead | None)
def get_character_profile(character_id: int, db: Session = Depends(get_db)):
    return db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == character_id))


@router.post("/{character_id}/profile/import", response_model=CharacterProfileRead)
def import_profile(character_id: int, payload: CharacterProfileImportRequest, db: Session = Depends(get_db)):
    try:
        return import_character_profile(
            db,
            character_id=character_id,
            source_text=payload.source_text,
            source_filename=payload.source_filename,
            merge_into_persona_prompt=payload.merge_into_persona_prompt)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/extract-docx-text", summary="从 DOCX 抽取纯文本")
async def extract_docx_text(file: UploadFile = File(...)):
    """供 Web「人物设定」导入：抽取正文到文本框，再走既有 profile/import 流程。"""
    from ..utils.docx_text import extract_docx_plain_text

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="空文件")
    try:
        text = extract_docx_plain_text(raw)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"DOCX 解析失败：{exc}") from exc
    if not text.strip():
        raise HTTPException(status_code=400, detail="未从文档中解析出可见文本")
    return {"text": text, "filename": file.filename or "document.docx"}


def _safe_export_filename(name: str, ext: str) -> str:
    import re

    base = re.sub(r"[^\w\-_.\u4e00-\u9fff]+", "_", (name or "character").strip())[:80] or "character"
    return f"{base}_portable.{ext}"


def _parse_portable_upload(filename: str, raw: bytes) -> dict:
    from ..utils.docx_text import extract_docx_plain_text

    fname = (filename or "").lower()
    if fname.endswith(".docx") or (len(raw) >= 2 and raw[:2] == b"PK"):
        try:
            text = extract_docx_plain_text(raw)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return portable_from_txt(text)
    if fname.endswith(".json") or (raw[:1] == b"{"):
        try:
            data = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=400, detail="JSON 解析失败") from exc
        if isinstance(data, list):
            raise HTTPException(status_code=400, detail="不支持的 JSON 列表格式")
        if data.get("kind") in (PORTABLE_KIND, "mojing_character_portable"):
            if int(data.get("version", 1)) != PORTABLE_VERSION:
                raise HTTPException(status_code=400, detail=f"暂不支持的便携版本: {data.get('version')}")
            return data
        data_root = data.get("data", data)
        internal = convert_v2_to_internal({"data": data_root})
        if not internal.get("name"):
            raise HTTPException(status_code=400, detail="角色卡缺少名称")
        return {
            "kind": PORTABLE_KIND,
            "version": PORTABLE_VERSION,
            "name": internal["name"],
            "persona_prompt": internal.get("persona_prompt") or "",
            "model_name": "",
            "api_base_url": "",
        }
    text = raw.decode("utf-8", errors="replace")
    return portable_from_txt(text)


def _persist_tavern_card_profile(db: Session, character: CharacterModel, card_root: dict) -> None:
    """将完整酒馆 V2 根对象写入 profile（与 DEFAULT_CARD 合并，供上下文命中与扩展字段保留）。"""
    internal = convert_v2_to_internal(card_root)
    merged = deepcopy(DEFAULT_CARD)
    merged["tavern_chara_card_v2"] = card_root
    data_blk = card_root.get("data") if isinstance(card_root.get("data"), dict) else {}
    if isinstance(data_blk, dict):
        ext = data_blk.get("extensions")
        if isinstance(ext, dict) and ext:
            merged["tavern_extensions"] = ext
    prof = db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == character.id))
    if prof is None:
        prof = CharacterProfileModel(character_id=character.id)
        db.add(prof)
    prof.source_filename = "chara_card_v2_import"
    prof.raw_persona_text = (internal.get("persona_prompt") or "")[:200000]
    prof.character_card_json = merged
    prof.character_card_markdown = "## 酒馆角色卡（导入原样保留）\n\n" + (internal.get("persona_prompt") or "")[:50000]
    prof.extracted_at = datetime.utcnow()


def _character_from_tavern_internal(db: Session, internal: dict) -> CharacterModel:
    name = allocate_unique_character_name(db, (internal.get("name") or "未命名").strip())
    persona = (internal.get("persona_prompt") or "").strip()
    return CharacterModel(
        name=name,
        persona_prompt=persona,
        api_key="",
        api_base_url="https://api.deepseek.com",
        model_name="deepseek-chat",
        temperature=0.9,
        max_tokens=1200,
        top_p=1.0,
        top_k=0,
        frequency_penalty=0.0,
        presence_penalty=0.0,
        repetition_penalty=1.0,
        avatar_color="#F97316",
    )


@router.post("/import-portable", summary="导入便携角色包（JSON/TXT/DOCX，与本应用导出同格式）", response_model=CharacterRead)
async def import_character_portable(file: UploadFile = File(...), db: Session = Depends(get_db)):
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="空文件")
    try:
        payload = _parse_portable_upload(file.filename or "", raw)
    except HTTPException:
        raise
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"解析失败：{exc}") from exc

    name = allocate_unique_character_name(db, str(payload.get("name") or "未命名"))
    persona = str(payload.get("persona_prompt") or "")
    character = CharacterModel(
        name=name,
        persona_prompt=persona,
        api_key="",
        api_base_url=str(payload.get("api_base_url") or "https://api.deepseek.com")[:255],
        model_name=str(payload.get("model_name") or "deepseek-chat")[:120],
        temperature=float(payload.get("temperature") or 0.9),
        max_tokens=int(payload.get("max_tokens") or 1200),
        top_p=float(payload.get("top_p") or 1.0),
        top_k=int(payload.get("top_k") or 0),
        frequency_penalty=float(payload.get("frequency_penalty") or 0.0),
        presence_penalty=float(payload.get("presence_penalty") or 0.0),
        repetition_penalty=float(payload.get("repetition_penalty") or 1.0),
        avatar_color=str(payload.get("avatar_color") or "#F97316")[:20],
    )
    db.add(character)
    db.commit()
    db.refresh(character)
    prof_in = payload.get("profile")
    if isinstance(prof_in, dict) and character.id:
        raw = str(prof_in.get("raw_persona_text") or "")
        md = str(prof_in.get("character_card_markdown") or "")
        cj = prof_in.get("character_card_json")
        if raw or md or (isinstance(cj, dict) and cj):
            existing = db.scalar(
                select(CharacterProfileModel).where(CharacterProfileModel.character_id == character.id)
            )
            if not existing:
                prof = CharacterProfileModel(
                    character_id=character.id,
                    source_filename=str(prof_in.get("source_filename") or "imported.json")[:255],
                    raw_persona_text=raw,
                    character_card_markdown=md,
                    character_card_json=cj if isinstance(cj, dict) else {},
                )
                db.add(prof)
                db.commit()
    db.refresh(character)
    return _character_read(character)


@router.get("/{character_id}/export-portable", summary="导出便携角色包（不含密钥）")
def export_character_portable(
    character_id: int,
    format: str = Query("json", description="json | txt | docx"),
    db: Session = Depends(get_db),
):
    fmt = (format or "json").lower()
    if fmt not in ("json", "txt", "docx"):
        raise HTTPException(status_code=400, detail="format 须为 json、txt 或 docx")
    character = db.scalar(
        select(CharacterModel)
        .where(CharacterModel.id == character_id)
        .options(joinedload(CharacterModel.profile), joinedload(CharacterModel.voice_profile))
    )
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    payload = build_portable_payload(character, character.profile)
    name = _safe_export_filename(character.name, fmt)
    if fmt == "json":
        body = portable_json_dumps(payload).encode("utf-8")
        return Response(
            content=body,
            media_type="application/json; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{name}"'},
        )
    if fmt == "txt":
        body = portable_to_txt(payload).encode("utf-8")
        return Response(
            content=body,
            media_type="text/plain; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{name}"'},
        )
    docx_bytes = write_plain_docx(portable_to_txt(payload))
    return Response(
        content=docx_bytes,
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@router.post("/{character_id}/export-portable-summary", summary="AI 摘要后导出（需设置页公共文字 API Key）")
def export_character_portable_summary(
    character_id: int,
    format: str = Query("json", description="json | txt | docx"),
    db: Session = Depends(get_db),
):
    fmt = (format or "json").lower()
    if fmt not in ("json", "txt", "docx"):
        raise HTTPException(status_code=400, detail="format 须为 json、txt 或 docx")
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")

    from openai import OpenAI

    from ..services.crypto_service import decrypt_api_key
    from ..services.image_service import _normalize_openai_compatible_base
    from ..services.system_config_service import get_local_config

    config = get_local_config(db)
    api_key = decrypt_api_key(config.get("public_text_api_key") or "")
    if not (api_key or "").strip():
        raise HTTPException(
            status_code=400,
            detail="AI 摘要导出需要先在「设置 → 公共 API」填写文字对话 API 密钥（public_text_api_key）。",
        )
    base = (config.get("public_text_base_url") or "").strip()
    model = (config.get("public_text_model") or "").strip() or "deepseek-chat"
    client_kw: dict = {"api_key": api_key, "timeout": 120.0}
    if base:
        client_kw["base_url"] = _normalize_openai_compatible_base(base) or base
    client = OpenAI(**client_kw)
    source_dump = collect_character_dump_for_summary(db, character)
    try:
        summary_payload = summarize_portable_with_public_llm(client=client, model=model, source_dump=source_dump)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"AI 摘要失败：{exc}") from exc

    fname = _safe_export_filename(character.name, fmt).replace("_portable.", "_summary.")
    if fmt == "json":
        body = portable_json_dumps(summary_payload).encode("utf-8")
        return Response(
            content=body,
            media_type="application/json; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{fname}"'},
        )
    if fmt == "txt":
        body = portable_to_txt(summary_payload).encode("utf-8")
        return Response(
            content=body,
            media_type="text/plain; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{fname}"'},
        )
    docx_bytes = write_plain_docx(portable_to_txt(summary_payload))
    return Response(
        content=docx_bytes,
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        headers={"Content-Disposition": f'attachment; filename="{fname}"'},
    )


@router.post("/import-card", summary="导入 PNG 角色卡", response_model=CharacterRead)
def import_character_card(file: UploadFile = File(...), db: Session = Depends(get_db)):
    """从 PNG 角色卡 V2 文件导入人物。"""
    if not (file.content_type or "").startswith("image/") and not file.filename.endswith(".png"):
        raise HTTPException(status_code=400, detail="仅支持 PNG 格式的角色卡")

    temp_dir = STORAGE_DIR / "temp"
    temp_dir.mkdir(parents=True, exist_ok=True)
    temp_path = temp_dir / f"import_card_{file.filename}"
    try:
        with temp_path.open("wb") as output:
            shutil.copyfileobj(file.file, output)
        png_bytes = temp_path.read_bytes()
        card_data = read_character_card_from_png_bytes(png_bytes)
        if card_data is None:
            raise HTTPException(status_code=400, detail="未找到角色卡数据（支持 tEXt/zTXt chara 块）")
        internal = convert_v2_to_internal(card_data)
        if not internal.get("name"):
            raise HTTPException(status_code=400, detail="角色卡缺少名称")
        character = _character_from_tavern_internal(db, internal)
        db.add(character)
        db.flush()
        _persist_tavern_card_profile(db, character, card_data)
        db.commit()
        db.refresh(character)
        return _character_read(character)
    finally:
        if temp_path.exists():
            temp_path.unlink()


@router.post("/import-card-json", summary="导入 JSON 角色卡", response_model=CharacterRead)
def import_character_card_json(file: UploadFile = File(...), db: Session = Depends(get_db)):
    """从 JSON 角色卡文件导入人物（Chub.ai 等平台通用格式）。"""
    if not file.filename.endswith(".json"):
        raise HTTPException(status_code=400, detail="仅支持 JSON 格式的角色卡")

    raw = file.file.read().decode("utf-8")
    try:
        card_data = json.loads(raw)
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="JSON 解析失败")

    data_root = card_data.get("data", card_data)
    internal = convert_v2_to_internal({"data": data_root})
    if not internal.get("name"):
        raise HTTPException(status_code=400, detail="角色卡缺少名称")
    persist_root = card_data if isinstance(card_data, dict) and card_data.get("spec") else {"spec": "chara_card_v2", "data": data_root}
    character = _character_from_tavern_internal(db, internal)
    db.add(character)
    db.flush()
    _persist_tavern_card_profile(db, character, persist_root)
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.post(
    "/preview-card-image",
    response_model=CharacterCardImagePreviewResult,
    summary="预览角色卡图（仅 public_image_*，不落库）",
)
async def preview_character_card_image(payload: CharacterCardImagePreviewBody, db: Session = Depends(get_db)):
    """供 Android 等本地人物 id 与服务器不一致时，仍用服务器已配置的公共生图线路生成预览 URL。"""
    from ..services.image_client_service import build_character_card_image_prompt, resolve_public_image_credentials
    from ..services.image_service import generate_image

    api_key, base_url, model = resolve_public_image_credentials(db)
    prompt = build_character_card_image_prompt(
        name=payload.name,
        persona_prompt=payload.persona_prompt,
        prompt_hint=payload.prompt_hint,
    )
    result = await generate_image(
        prompt=prompt,
        api_key=api_key,
        base_url=base_url,
        model=model,
        size=(payload.size or "1024x1792").strip() or "1024x1792",
    )
    return CharacterCardImagePreviewResult(
        urls=list(result.get("urls") or []),
        revised_prompt=str(result.get("revised_prompt") or ""),
        error=(result.get("error") or None),
    )


@router.post(
    "/{character_id}/generate-card-image",
    response_model=CharacterRead,
    summary="生成角色卡竖图并写入 card_image_path",
)
async def generate_character_card_image(
    character_id: int,
    payload: CharacterCardImageGenBody,
    db: Session = Depends(get_db),
):
    from ..services.image_client_service import (
        build_character_card_image_prompt,
        persist_first_generated_image,
        resolve_character_image_credentials,
    )
    from ..services.image_service import generate_image

    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")

    api_key, base_url, model = resolve_character_image_credentials(db, character)
    prompt = build_character_card_image_prompt(
        name=character.name,
        persona_prompt=character.persona_prompt or "",
        prompt_hint=payload.prompt_hint,
    )
    result = await generate_image(
        prompt=prompt,
        api_key=api_key,
        base_url=base_url,
        model=model,
        size=(payload.size or "1024x1792").strip() or "1024x1792",
    )
    err = result.get("error")
    urls = list(result.get("urls") or [])
    if err and not urls:
        raise HTTPException(status_code=502, detail=str(err))

    rel = await persist_first_generated_image(
        urls=urls,
        dest_subdir="generated/characters/cards",
        file_prefix=f"char_{character_id}",
        download_bearer=api_key,
    )
    character.card_image_path = rel
    db.commit()
    db.refresh(character)
    return _character_read(character)


@router.get("/{character_id}/export-card", summary="导出角色卡")
def export_character_card(character_id: int, db: Session = Depends(get_db)):
    """导出人物为 PNG 角色卡 V2 格式。"""
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")

    card_data = convert_internal_to_v2(character)

    avatar_path = None
    if character.avatar_image_path:
        avatar_candidate = STORAGE_DIR / character.avatar_image_path
        if avatar_candidate.exists():
            avatar_path = avatar_candidate

    export_dir = STORAGE_DIR / "exports" / "cards"
    export_dir.mkdir(parents=True, exist_ok=True)
    output_path = export_dir / f"{character.name}_card.png"

    if avatar_path and avatar_path.suffix.lower() == ".png":
        write_character_card_to_png(avatar_path, card_data, output_path)
    else:
        # 没有 PNG 头像时，创建一个纯色 PNG 作为载体
        from PIL import Image as PILImage
        img = PILImage.new("RGBA", (512, 512), (30, 41, 49, 255))
        img.save(str(output_path), "PNG")
        write_character_card_to_png(output_path, card_data, output_path)

    from fastapi.responses import FileResponse
    return FileResponse(
        str(output_path),
        media_type="image/png",
        filename=f"{character.name}_chara_card_v2.png")


@router.delete("/{character_id}", summary="删除角色")
def delete_character(character_id: int, db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    db.delete(character)
    db.commit()
    return {"ok": True}


@router.patch("/{character_id}/favorite", summary="切换收藏")
def toggle_favorite(character_id: int, db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    character.favorite = not character.favorite
    db.commit()
    return {"ok": True, "favorite": bool(character.favorite)}


@router.post("/import-url", summary="从链接导入")
def import_character_from_url(payload: dict = {"url": ""}, db: Session = Depends(get_db)):
    """从 URL 下载 PNG 角色卡并导入。"""
    url = payload.get("url", "")
    if not url:
        raise HTTPException(status_code=400, detail="URL 不能为空")
    import tempfile
    import urllib.request

    tmp_path: Path | None = None
    try:
        tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        tmp_path = Path(tmp.name)
        tmp.close()
        urllib.request.urlretrieve(url, str(tmp_path))
        png_bytes = tmp_path.read_bytes()
        card_data = read_character_card_from_png_bytes(png_bytes)
        if card_data is None:
            raise HTTPException(status_code=400, detail="未找到角色卡数据或格式不受支持")
        internal = convert_v2_to_internal(card_data)
        if not internal.get("name"):
            raise HTTPException(status_code=400, detail="角色卡缺少名称")
        character = _character_from_tavern_internal(db, internal)
        db.add(character)
        db.flush()
        _persist_tavern_card_profile(db, character, card_data)
        db.commit()
        db.refresh(character)
        return _character_read(character)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"从 URL 导入失败: {str(e)}") from e
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass
