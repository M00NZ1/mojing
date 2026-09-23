from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from typing import Awaitable, Callable

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from ..models import (
    CharacterModel,
    ChatSessionModel,
    MessageModel,
    StoryGenerationDraftModel,
    SessionParticipantModel,
    SessionWorldModel,
    StoryRequestReceiptModel,
    WorldEncyclopediaModel,
    WorldTemplateModel,
)
from ..schemas import StoryWritingChapter, StoryWritingRequest, StoryWritingResult
from .llm_client import build_public_async_text_client
from .llm_retry import safe_async_non_streaming_call
from .story_rules import (
    STORY_CANON_RULES,
    build_story_world_prompt,
    deepseek_story_instruction,
    filter_story_choices,
    story_temperature,
)
from .story_request_receipt import RECEIPT_VERSION, claim_request, find_receipt, payload_hash, release_request
from .story_generation_draft import load_generation_draft, save_generation_draft


@dataclass(frozen=True)
class StoryDraft:
    title: str
    chapters: list[StoryWritingChapter]
    next_choices: list[str]


class StoryGenerationCancelled(RuntimeError):
    """客户端已离开或主动停止，且正式会话尚未开始写入。"""


class GeneratedStoryNotSaved(Exception):
    """The model finished, but neither the draft nor the session could be stored."""

    def __init__(self, draft: StoryDraft, request_id: str):
        self.request_id = request_id
        self.text = "\n\n".join([draft.title] + [
            f"{chapter.title}\n\n{chapter.content}" for chapter in draft.chapters
        ])
        super().__init__("正文已生成，但本机数据库暂时无法保存。请先复制全文。")


DisconnectCheck = Callable[[], Awaitable[bool]]


async def _cancelable_story_call(
    client,
    *,
    model: str,
    messages: list[dict[str, str]],
    temperature: float,
    max_tokens: int,
    is_disconnected: DisconnectCheck | None,
) -> str:
    if is_disconnected is not None and await is_disconnected():
        raise StoryGenerationCancelled("小说生成已停止")

    call_task = asyncio.create_task(
        safe_async_non_streaming_call(
            client,
            model=model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
        )
    )
    try:
        if is_disconnected is None:
            return await call_task
        while True:
            done, _ = await asyncio.wait({call_task}, timeout=0.1)
            if call_task in done:
                return call_task.result()
            if await is_disconnected():
                call_task.cancel()
                try:
                    await call_task
                except asyncio.CancelledError:
                    pass
                raise StoryGenerationCancelled("小说生成已停止")
    finally:
        if not call_task.done():
            call_task.cancel()
            try:
                await call_task
            except asyncio.CancelledError:
                pass


def _extract_json_object(raw: str) -> dict:
    text = (raw or "").strip()
    fence = re.search(r"```(?:json)?\s*(.*?)\s*```", text, re.IGNORECASE | re.DOTALL)
    if fence:
        text = fence.group(1).strip()
    elif "{" in text and "}" in text:
        text = text[text.find("{") : text.rfind("}") + 1]
    try:
        data = json.loads(text)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="大模型返回的小说正文不是有效 JSON，请重试") from exc
    if not isinstance(data, dict):
        raise HTTPException(status_code=400, detail="大模型返回的小说正文格式不完整，请重试")
    return data


def parse_story_draft(raw: str, chapter_count: int, premise: str = "") -> StoryDraft:
    data = _extract_json_object(raw)
    raw_chapters = data.get("chapters")
    if not isinstance(raw_chapters, list) or len(raw_chapters) != chapter_count:
        raise HTTPException(status_code=400, detail=f"大模型应返回恰好 {chapter_count} 章正文，请重试")
    try:
        chapters = [
            StoryWritingChapter.model_validate(
                {
                    "number": index,
                    "title": str(item.get("title") or f"第 {index} 章").strip(),
                    "content": str(item.get("content") or item.get("narrative") or "").strip(),
                }
            )
            for index, item in enumerate(raw_chapters, start=1)
            if isinstance(item, dict)
        ]
    except Exception as exc:
        raise HTTPException(status_code=400, detail="大模型返回的章节字段不完整，请重试") from exc
    if len(chapters) != chapter_count:
        raise HTTPException(status_code=400, detail="大模型返回了空章节，请重试")
    choices = filter_story_choices(data.get("next_choices") or [], premise)
    if len(choices) < 2:
        raise HTTPException(status_code=400, detail="大模型没有生成可用的后续剧情选项，请重试")
    title = str(data.get("title") or "").strip() or chapters[0].title
    return StoryDraft(title=title, chapters=chapters, next_choices=choices[:4])


def _load_context(
    db: Session,
    template_id: str | None,
    encyclopedia_id: int | None,
    character_ids: list[int],
) -> str:
    from .world_context_source import uses_canonical_world

    context: list[str] = []
    if template_id:
        template = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
        if template is None:
            raise HTTPException(status_code=404, detail="选中的世界模板不存在")
        if not uses_canonical_world(db, template_id, encyclopedia_id):
            context.append(f"世界模板：{template.label}\n{template.world_prompt}")
    if encyclopedia_id is not None:
        encyclopedia = db.get(WorldEncyclopediaModel, encyclopedia_id)
        if encyclopedia is None:
            raise HTTPException(status_code=404, detail="选中的世界百科库不存在")
        context.append(f"世界百科：{encyclopedia.name}\n{encyclopedia.world_prompt}")
    for character_id in dict.fromkeys(character_ids):
        character = db.get(CharacterModel, character_id)
        if character is None:
            raise HTTPException(status_code=404, detail=f"角色 {character_id} 不存在")
        context.append(f"角色：{character.name}\n{character.persona_prompt}")
    return "\n\n".join(context)


def _filter_recovered_references(db: Session, payload: StoryWritingRequest) -> StoryWritingRequest:
    """保留原请求 hash，避免暂存重放引用已删除的本地资料。"""
    template_id = payload.template_id
    if template_id and db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id)) is None:
        template_id = None
    encyclopedia_id = payload.encyclopedia_id
    if encyclopedia_id is not None and db.get(WorldEncyclopediaModel, encyclopedia_id) is None:
        encyclopedia_id = None
    character_ids = [character_id for character_id in dict.fromkeys(payload.character_ids) if db.get(CharacterModel, character_id) is not None]
    return payload.model_copy(update={"template_id": template_id, "encyclopedia_id": encyclopedia_id, "character_ids": character_ids})


def _build_writing_prompt(payload: StoryWritingRequest, context: str, model: str = "") -> str:
    return f"""请把用户提供的故事背景直接写成长篇小说开篇，共 {payload.chapter_count} 章。
只返回合法 JSON，不要 Markdown 或解释：
{{"title":"小说名","chapters":[{{"title":"第一章标题","content":"完整小说正文"}}],"next_choices":["后续走向一","后续走向二","后续走向三"]}}

要求：
1. 这是小说正文，不是大纲、分析、候选方案或角色聊天。
2. 每章必须有场景、动作、人物对话、心理与因果推进，章与章连续；每章约 900～1800 个中文字符。
3. 严格保持已提供人物设定；未指定的细节可以自然补全，但不要替用户终结主线。
4. 最后一章停在可继续的位置，next_choices 必须根据刚生成的情节给出 2～4 个不同后续走向，每次动态生成。

{STORY_CANON_RULES}
{deepseek_story_instruction(model)}

故事背景：
{payload.premise.strip()}

接下来希望发生的事情：
{payload.direction.strip() or '请从故事背景自然展开开篇。'}

文风与节奏：
{payload.tone.strip() or '有画面感、人物动机清楚、适合连续长篇创作。'}

已有世界与人物资料：
{context or '无额外资料，以用户输入为准。'}"""


async def _create_story_session_impl(
    db: Session,
    payload: StoryWritingRequest,
    *,
    is_disconnected: DisconnectCheck | None = None,
    request_id: str | None = None,
    request_payload_hash: str | None = None,
    stored_draft: StoryDraft | None = None,
    stored_context: str | None = None,
) -> StoryWritingResult:
    client = None
    recovery_available = stored_draft is not None
    draft_write_failed = False
    try:
        context = stored_context if stored_context is not None else _load_context(db, payload.template_id, payload.encyclopedia_id, payload.character_ids)
        if stored_draft is not None:
            draft = stored_draft
        else:
            client, model = build_public_async_text_client(db)
            raw = await _cancelable_story_call(
                client,
                model=model,
                messages=[
                    {
                        "role": "system",
                        "content": "你是长篇小说作者。直接写有连续性的小说正文，并严格遵守 JSON 输出协议。",
                    },
                    {"role": "user", "content": _build_writing_prompt(payload, context, model)},
                ],
                temperature=story_temperature(model),
                max_tokens=8000,
                is_disconnected=is_disconnected,
            )
            draft = parse_story_draft(raw, payload.chapter_count, payload.premise)
            if request_id is not None and request_payload_hash is not None:
                try:
                    stored_row = save_generation_draft(
                        db,
                        request_id=request_id,
                        payload=payload,
                        payload_hash=request_payload_hash,
                        context_text=context,
                        draft_json={
                            "title": draft.title,
                            "chapters": [chapter.model_dump(mode="json") for chapter in draft.chapters],
                            "next_choices": draft.next_choices,
                        },
                    )
                except (HTTPException, SQLAlchemyError) as exc:
                    if isinstance(exc, HTTPException) and exc.status_code != 503:
                        raise
                    # Keep the finished model output in memory and try the
                    # ordinary atomic session + receipt write once.
                    draft_write_failed = True
                else:
                    draft = parse_story_draft(json.dumps(stored_row.draft_json, ensure_ascii=False), payload.chapter_count, payload.premise)
                    context = stored_row.context_text
                    recovery_available = True
            await client.close()
            client = None
        if is_disconnected is not None and await is_disconnected():
            raise StoryGenerationCancelled("小说生成已停止")

        session = ChatSessionModel(title=draft.title[:80], summary=payload.premise.strip())
        db.add(session)
        db.flush()
        db.add(
            SessionWorldModel(
                session_id=session.id,
                encyclopedia_id=payload.encyclopedia_id,
                template_id=payload.template_id or "custom",
                gameplay_mode="小说创作",
                world_prompt=build_story_world_prompt(payload.premise, context),
                narrator_enabled=True,
                narrator_name="小说作者",
                choice_generation_enabled=True,
                max_choice_count=3,
            )
        )
        for sort_order, character_id in enumerate(dict.fromkeys(payload.character_ids)):
            db.add(SessionParticipantModel(session_id=session.id, character_id=character_id, sort_order=sort_order))

        setup = "\n\n".join(
            part
            for part in [
                f"【故事背景】\n{payload.premise.strip()}",
                f"【接下来希望发生】\n{payload.direction.strip()}" if payload.direction.strip() else "",
                f"【文风与节奏】\n{payload.tone.strip()}" if payload.tone.strip() else "",
            ]
            if part
        )
        db.add(
            MessageModel(
                session_id=session.id,
                speaker_type="user",
                content=setup,
                structured_content={"mode": "story_writing", "kind": "setup"},
            )
        )
        for index, chapter in enumerate(draft.chapters):
            is_last = index == len(draft.chapters) - 1
            db.add(
                MessageModel(
                    session_id=session.id,
                    speaker_type="narrator",
                    content=f"## {chapter.title}\n\n{chapter.content}",
                    structured_content={
                        "mode": "story_writing",
                        "chapter_number": chapter.number,
                        "chapter_title": chapter.title,
                        "choices": draft.next_choices if is_last else [],
                    },
                )
            )
        result = StoryWritingResult(
            session_id=session.id,
            title=draft.title,
            chapter_count=len(draft.chapters),
        )
        if request_id is not None:
            db.add(StoryRequestReceiptModel(
                request_id=request_id,
                receipt_version=RECEIPT_VERSION,
                payload_hash=request_payload_hash or "",
                session_id=session.id,
                session_created_at=session.created_at,
                result_json=result.model_dump(mode="json"),
            ))
            draft_row = db.get(StoryGenerationDraftModel, request_id)
            if draft_row is not None:
                db.delete(draft_row)
        db.commit()
        return result
    except IntegrityError:
        db.rollback()
        if request_id is not None and request_payload_hash is not None:
            try:
                recovered = find_receipt(db, request_id, request_payload_hash)
            except SQLAlchemyError:
                if draft_write_failed:
                    raise GeneratedStoryNotSaved(draft, request_id)
                raise
            if recovered is not None:
                return recovered
            if recovery_available:
                raise HTTPException(status_code=503, detail="正文已完整暂存，但会话保存失败，请稍后重试")
            if draft_write_failed:
                raise GeneratedStoryNotSaved(draft, request_id)
        raise
    except SQLAlchemyError as exc:
        db.rollback()
        if recovery_available:
            raise HTTPException(status_code=503, detail="正文已完整暂存，但会话保存失败，请稍后重试") from exc
        if draft_write_failed:
            raise GeneratedStoryNotSaved(draft, request_id) from exc
        raise
    except HTTPException as exc:
        db.rollback()
        if draft_write_failed:
            raise GeneratedStoryNotSaved(draft, request_id) from exc
        raise
    except StoryGenerationCancelled:
        db.rollback()
        raise
    except asyncio.CancelledError:
        db.rollback()
        raise
    except Exception as exc:
        db.rollback()
        if draft_write_failed:
            raise GeneratedStoryNotSaved(draft, request_id) from exc
        raise
    finally:
        if client is not None:
            await client.close()


async def create_story_session(
    db: Session,
    payload: StoryWritingRequest,
    *,
    is_disconnected: DisconnectCheck | None = None,
) -> StoryWritingResult:
    """Generate once per request_id while keeping model wait outside a write transaction."""
    request_id = payload.request_id
    if request_id is None:
        return await _create_story_session_impl(db, payload, is_disconnected=is_disconnected)
    expected_hash = payload_hash(payload)
    cached = find_receipt(db, request_id, expected_hash)
    if cached is not None:
        return cached
    db.rollback()
    await claim_request(request_id)
    try:
        cached = find_receipt(db, request_id, expected_hash)
        if cached is not None:
            return cached
        db.rollback()
        stored = load_generation_draft(db, request_id, expected_hash)
        if stored is not None:
            stored_payload = _filter_recovered_references(
                db,
                StoryWritingRequest.model_validate({**stored.payload_json, "request_id": request_id}),
            )
            stored_story = parse_story_draft(json.dumps(stored.draft_json, ensure_ascii=False), stored_payload.chapter_count, stored_payload.premise)
            return await _create_story_session_impl(
                db,
                stored_payload,
                is_disconnected=is_disconnected,
                request_id=request_id,
                request_payload_hash=expected_hash,
                stored_draft=stored_story,
                stored_context=stored.context_text,
            )
        return await _create_story_session_impl(
            db,
            payload,
            is_disconnected=is_disconnected,
            request_id=request_id,
            request_payload_hash=expected_hash,
        )
    finally:
        await release_request(request_id)
