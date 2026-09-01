"""
硅基流动双路线 QA（人物 + 工坊 + 会话 + 生图 + 语音合成 + 旁白）。

用法（密钥勿写入仓库，仅环境变量）:
  cd mojing
  set PYTHONPATH=backend
  set SILICONFLOW_API_KEY=sk-...
  python scripts/sf_qa_routes.py

依赖: 本机已启动 uvicorn backend.app.main:app --port 8000
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import httpx  # noqa: E402
from sqlalchemy import select  # noqa: E402

from app.database import SessionLocal  # noqa: E402
from app.models import CharacterModel, MessageModel, WorldTemplateModel  # noqa: E402
from app.services.system_config_service import get_local_config, set_local_config  # noqa: E402

BASE = os.environ.get("MOJING_QA_API_BASE", "http://127.0.0.1:8000/api")
SF_BASE = "https://api.siliconflow.cn/v1"
# 模型名以硅基控制台「模型广场」为准；以下为文档/广场常见 ID
SF_CHAT = os.environ.get("SILICONFLOW_CHAT_MODEL", "deepseek-ai/DeepSeek-V3")
SF_IMAGE = os.environ.get("SILICONFLOW_IMAGE_MODEL", "Kwai-Kolors/Kolors")
TIMEOUT = httpx.Timeout(300.0, connect=30.0)


def die(msg: str, code: int = 1) -> None:
    print(f"[FAIL] {msg}", file=sys.stderr)
    raise SystemExit(code)


def merge_public_config(db, api_key: str) -> None:
    cfg = get_local_config(db)
    cfg["public_text_api_key"] = api_key
    cfg["public_text_base_url"] = SF_BASE
    cfg["public_text_model"] = SF_CHAT
    cfg["public_image_api_key"] = api_key
    cfg["public_image_base_url"] = SF_BASE
    cfg["public_image_model"] = SF_IMAGE
    cfg["public_voice_api_key"] = api_key
    cfg["public_voice_base_url"] = SF_BASE
    cfg["public_voice_model"] = os.environ.get("SILICONFLOW_STT_MODEL", "FunAudioLLM/SenseVoiceSmall")
    set_local_config(db, cfg)


def http_json(client: httpx.Client, method: str, path: str, **kw) -> dict:
    r = client.request(method, f"{BASE}{path}", **kw)
    if r.status_code >= 400:
        die(f"{method} {path} -> {r.status_code}: {r.text[:800]}")
    if not r.content.strip():
        return {}
    try:
        return r.json()
    except json.JSONDecodeError:
        die(f"{method} {path} 非 JSON: {r.text[:400]}")


def sse_read_generate(client: httpx.Client, session_id: int, body: dict) -> list[dict]:
    events: list[dict] = []
    with client.stream(
        "POST",
        f"{BASE}/sessions/{session_id}/generate/stream",
        json=body,
        headers={"Accept": "text/event-stream"},
        timeout=TIMEOUT,
    ) as resp:
        if resp.status_code >= 400:
            die(f"generate/stream -> {resp.status_code}: {resp.read().decode()[:800]}")
        buf = ""
        for chunk in resp.iter_text():
            buf += chunk
            while "\n\n" in buf:
                block, buf = buf.split("\n\n", 1)
                for line in block.split("\n"):
                    line = line.strip()
                    if line.startswith("data:"):
                        raw = line[5:].strip()
                        if raw == "[DONE]":
                            continue
                        try:
                            events.append(json.loads(raw))
                        except json.JSONDecodeError:
                            pass
    return events


def main() -> None:
    api_key = (os.environ.get("SILICONFLOW_API_KEY") or "").strip()
    if not api_key:
        die("请设置环境变量 SILICONFLOW_API_KEY（不要把密钥写入仓库）")

    with httpx.Client(timeout=TIMEOUT) as client:
        h = client.get("http://127.0.0.1:8000/health")
        if h.status_code != 200:
            die("后端未响应 /health，请先启动 uvicorn backend.app.main:app --port 8000")

    db = SessionLocal()
    try:
        merge_public_config(db, api_key)
        print("[OK] 已合并写入 local_config（文本/生图/语音 STT 公共硅基端点）")
    finally:
        db.close()

    suffix = uuid.uuid4().hex[:8]
    char_name = f"QA硅基人物_{suffix}"
    template_id = f"qa_sf_world_{suffix}"

    with httpx.Client(timeout=TIMEOUT) as client:
        # --- 路线一：人物 ---
        created = http_json(
            client,
            "POST",
            "/characters",
            json={
                "name": char_name,
                "persona_prompt": "初始人设一句话。",
                "api_key": api_key,
                "api_base_url": SF_BASE,
                "model_name": SF_CHAT,
                "temperature": 0.7,
                "max_tokens": 800,
            },
        )
        cid = int(created["id"])
        db = SessionLocal()
        try:
            row = db.get(CharacterModel, cid)
            if not row or row.name != char_name:
                die("人物落库校验失败（创建）")
        finally:
            db.close()
        print(f"[OK] 人物创建并落库 id={cid}")

        http_json(
            client,
            "PUT",
            f"/characters/{cid}",
            json={
                "name": char_name,
                "persona_prompt": "已编辑：带标签 #QA_SF_EDIT",
                "api_key": api_key,
                "api_base_url": SF_BASE,
                "model_name": SF_CHAT,
                "temperature": 0.7,
                "max_tokens": 800,
            },
        )
        db = SessionLocal()
        try:
            row = db.get(CharacterModel, cid)
            if not row or "#QA_SF_EDIT" not in (row.persona_prompt or ""):
                die("人物落库校验失败（编辑）")
        finally:
            db.close()
        print("[OK] 人物编辑已落库")

        db = SessionLocal()
        try:
            row = db.get(CharacterModel, cid)
            persona_before = (row.persona_prompt or "") if row else ""
        finally:
            db.close()

        ac = http_json(
            client,
            "POST",
            "/ai/complete",
            json={
                "target_type": "character",
                "target_data": {"name": char_name, "persona_prompt": persona_before},
                "fields_to_complete": ["persona_prompt"],
                "character_id": cid,
                "extra_context": "请用中文补充 2～4 句性格与说话风格，不要改原名。",
            },
        )
        if ac.get("error"):
            die(f"AI 一键补全人物失败: {ac.get('error')}")
        completed = ac.get("completed_fields") or {}
        if not completed.get("persona_prompt"):
            die("AI 补全未返回 persona_prompt")
        merged_persona = persona_before + "\n" + str(completed["persona_prompt"])
        http_json(
            client,
            "PUT",
            f"/characters/{cid}",
            json={
                "name": char_name,
                "persona_prompt": merged_persona[:12000],
                "api_key": api_key,
                "api_base_url": SF_BASE,
                "model_name": SF_CHAT,
                "temperature": 0.7,
                "max_tokens": 800,
            },
        )
        print("[OK] AI 一键补全 persona 已写回人物")

        voices = http_json(client, "GET", "/voices")
        vid = None
        for v in voices:
            if v.get("provider") == "edge_tts_builtin":
                vid = int(v["id"])
                break
        if vid is None and voices:
            vid = int(voices[0]["id"])
        if vid is not None:
            http_json(client, "POST", f"/characters/{cid}/bind-voice", json={"voice_profile_id": vid})
            print(f"[OK] 已绑定声线 voice_profile_id={vid}")

        # --- 路线二：工坊（世界模板）---
        http_json(
            client,
            "POST",
            "/worlds/templates",
            json={
                "template_id": template_id,
                "label": f"QA工坊_{suffix}",
                "category": "测试",
                "summary": "手工摘要一行",
                "gameplay_mode": "自由剧情",
                "world_prompt": "手工世界提示：冷色调港口城市。",
                "cover_image_path": "",
                "suggested_choices": ["继续调查", "离开港口"],
                "anti_cheat_prompt": "禁止凭空获得神器。",
            },
        )
        db = SessionLocal()
        try:
            wt = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
            if not wt or wt.summary != "手工摘要一行":
                die("工坊模板落库失败（创建）")
        finally:
            db.close()
        print(f"[OK] 工坊模板创建并落库 template_id={template_id}")

        http_json(
            client,
            "PUT",
            f"/worlds/templates/{template_id}",
            json={
                "label": f"QA工坊已改_{suffix}",
                "category": "测试",
                "summary": "编辑后的摘要",
                "gameplay_mode": "自由剧情",
                "world_prompt": "编辑后的世界提示：港口与密教线索。",
                "cover_image_path": "",
                "suggested_choices": ["继续调查", "与线人接头"],
                "anti_cheat_prompt": "禁止凭空获得神器。",
            },
        )
        db = SessionLocal()
        try:
            wt = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
            if not wt or "编辑后的摘要" not in (wt.summary or ""):
                die("工坊模板落库失败（编辑）")
        finally:
            db.close()
        print("[OK] 工坊模板编辑已落库")

        gen_world = http_json(
            client,
            "POST",
            "/worlds/generate",
            json={
                "character_id": cid,
                "template_id": f"qa_sf_autogen_{suffix}",
                "world_type": "科幻",
                "core_theme": "近地轨道科研站出现不明信号，需排查风险",
                "tone": "偏硬科幻",
                "extra_requirements": "只要精炼设定，不要冗长文学描写。",
                "person_name_count": 4,
                "place_name_count": 4,
                "item_name_count": 3,
                "lore_entry_count": 3,
                "auto_save": True,
                "label": f"QA_AI世界_{suffix}",
            },
        )
        ai_tid = (gen_world.get("template") or {}).get("template_id") or ""
        if not ai_tid:
            die(f"AI 生成世界未返回 template_id: {str(gen_world)[:500]}")
        print(f"[OK] AI 一键生成世界已保存 template_id={ai_tid}")

        sess = http_json(
            client,
            "POST",
            "/sessions",
            json={
                "title": f"QA硅基会话_{suffix}",
                "template_id": ai_tid,
                "narrator_enabled": True,
                "narrator_name": "旁白",
            },
        )
        sid = int(sess["id"])
        http_json(client, "POST", f"/sessions/{sid}/participants", json={"character_id": cid})

        world = http_json(client, "GET", f"/sessions/{sid}/world")
        http_json(
            client,
            "PUT",
            f"/sessions/{sid}/world",
            json={
                "encyclopedia_id": world.get("encyclopedia_id"),
                "world_prompt": world.get("world_prompt") or "",
                "template_id": world.get("template_id") or ai_tid,
                "gameplay_mode": world.get("gameplay_mode") or "自由剧情",
                "narrator_enabled": True,
                "narrator_name": "旁白",
                "choice_generation_enabled": world.get("choice_generation_enabled", True),
                "max_choice_count": world.get("max_choice_count") or 3,
                "suggested_choices_json": world.get("suggested_choices_json") or [],
                "anti_cheat_enabled": world.get("anti_cheat_enabled", True),
                "anti_cheat_prompt": world.get("anti_cheat_prompt") or "",
                "auto_sediment_enabled": world.get("auto_sediment_enabled", True),
                "sediment_interval": world.get("sediment_interval") or 20,
            },
        )
        print(f"[OK] 会话已绑定人物与世界 sid={sid}")

        ev = sse_read_generate(
            client,
            sid,
            {
                "user_message": "用两三句中文回应：你是谁？当前环境有什么异常？",
                "include_narrator": True,
                "branch_id": "main",
            },
        )
        types = [e.get("type") for e in ev]
        errs = [e.get("message") for e in ev if e.get("type") == "error"]
        if "message_end" not in types:
            die(f"SSE 未正常结束: types={types[-25:]} errors={errs}")
        narr_ok = any(
            e.get("type") == "message_end"
            and (e.get("message") or {}).get("speaker_type") == "narrator"
            for e in ev
        )
        if not narr_ok:
            print("[WARN] 未在 SSE 中捕获 speaker_type=narrator 的 message_end（若旁白关闭或模型报错会出现）")
        else:
            print("[OK] 旁白流式生成已出现 narrator message_end")

        db = SessionLocal()
        try:
            last_ai = db.scalars(
                select(MessageModel)
                .where(MessageModel.session_id == sid, MessageModel.speaker_type == "character")
                .order_by(MessageModel.id.desc())
            ).first()
            if not last_ai:
                die("未找到角色 AI 消息")
            mid = int(last_ai.id)
        finally:
            db.close()

        syn = http_json(client, "POST", f"/voices/synthesize/message/{mid}")
        clips = syn.get("clips") or []
        if not clips:
            die("语音合成未返回 clips")
        print(f"[OK] 消息转语音 clips={len(clips)} kinds={[c.get('kind') for c in clips]}")

        img = http_json(
            client,
            "POST",
            "/images/generate",
            json={
                "prompt": "科幻空间站舷窗外地球蓝光，简洁插画",
                "api_key": api_key,
                "base_url": SF_BASE,
                "model": SF_IMAGE,
                "size": "1024x1024",
            },
        )
        urls = img.get("urls") or []
        if not urls:
            die(f"生图失败: {img.get('error') or img}")
        if not re.match(r"^https?://", urls[0]):
            die(f"生图 URL 异常: {urls[0][:120]}")
        print(f"[OK] 硅基生图成功 url_prefix={urls[0][:48]}...")

    print("\n全部步骤完成。建议在本机控制台轮换已出现在聊天记录中的密钥。")


if __name__ == "__main__":
    main()
