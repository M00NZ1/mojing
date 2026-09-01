"""
LLM 调用统一重试/退避工具 + 结构化日志。
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from datetime import datetime, timezone
from typing import Any, Callable

import openai
from tenacity import (
    retry,
    retry_if_exception,
    stop_after_attempt,
    wait_exponential,
)

logger = logging.getLogger(__name__)

# ============================================================
# 结构化日志
# ============================================================

LOG_EVENTS: list[dict] = []


def log_llm_event(event_type: str, **kwargs):
    """记录一次 LLM 调用事件到内存缓冲区。"""
    event = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "event_type": event_type,
        **kwargs,
    }
    LOG_EVENTS.append(event)
    safe_kwargs = {k: v for k, v in kwargs.items() if k not in ("response", "prompt_messages")}
    logger.info("[LLM] %s: %s", event_type, json.dumps(safe_kwargs, default=str))


def get_llm_events(limit: int = 100) -> list[dict]:
    return list(LOG_EVENTS[-limit:])


def clear_llm_events():
    LOG_EVENTS.clear()


# ============================================================
# 重试/退避配置
# ============================================================

RETRYABLE_ERRORS = (
    openai.APITimeoutError,
    openai.APIConnectionError,
    openai.RateLimitError,
    openai.InternalServerError,
)

NON_RETRYABLE_ERRORS = (
    openai.AuthenticationError,
    openai.BadRequestError,
    openai.PermissionDeniedError,
    openai.NotFoundError,
    openai.UnprocessableEntityError,
)


def _is_retryable_error(exception: BaseException) -> bool:
    if isinstance(exception, NON_RETRYABLE_ERRORS):
        return False
    if isinstance(exception, RETRYABLE_ERRORS):
        return True
    if isinstance(exception, openai.APIStatusError):
        status_code = int(exception.status_code)
        return status_code in {408, 409, 429} or status_code >= 500
    if isinstance(exception, openai.APIError):
        return False
    if isinstance(exception, (ConnectionError, TimeoutError)):
        return True
    msg = str(exception).lower()
    if any(kw in msg for kw in ["timeout", "rate limit", "too many requests", "internal server", "bad gateway", "service unavailable", "502", "503", "429"]):
        return True
    return False


def _classify_error(exception: Exception) -> str:
    msg = str(exception).lower()
    if isinstance(exception, openai.RateLimitError) or "429" in msg or "rate limit" in msg:
        return "请求频率超限，请稍后再试。"
    if isinstance(exception, openai.APITimeoutError) or "timeout" in msg:
        return "请求大模型超时，已自动重试。"
    if isinstance(exception, (openai.APIConnectionError, ConnectionError)) or "connection" in msg:
        return "与大模型服务连接中断。"
    if isinstance(exception, openai.AuthenticationError) or "401" in msg or "auth" in msg:
        return "API Key 认证失败，请检查配置。"
    if isinstance(exception, openai.PermissionDeniedError) or "403" in msg:
        return "接口拒绝访问，请检查 API Key 权限或服务策略。"
    if isinstance(exception, openai.NotFoundError) or "404" in msg:
        return "找不到指定的模型或接口，请检查服务地址和模型名称。"
    if isinstance(exception, (openai.BadRequestError, openai.UnprocessableEntityError)) or "400" in msg or "422" in msg:
        return "请求参数错误，请检查模型配置。"
    if isinstance(exception, openai.InternalServerError) or "500" in msg or "502" in msg or "503" in msg:
        return "大模型服务暂时不可用，已自动重试。"
    return f"调用失败: {exception}"


def _handle_retry_exhausted(exception: Exception) -> None:
    log_llm_event("llm_retry_exhausted", error=str(exception))
    user_msg = _classify_error(exception)
    if _is_retryable_error(exception):
        raise RuntimeError(f"{user_msg}（已自动重试 3 次，仍失败）") from exception
    raise exception


llm_retry_decorator = retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=2, max=15),
    retry=retry_if_exception(_is_retryable_error),
    retry_error_callback=lambda retry_state: _handle_retry_exhausted(retry_state.outcome.exception()),
    before_sleep=lambda retry_state: log_llm_event(
        "llm_retry",
        attempt=retry_state.attempt_number,
        wait_seconds=retry_state.next_action.sleep if retry_state.next_action else 0,
        error=str(retry_state.outcome.exception()),
    ),
)


def safe_llm_call(call_fn: Callable, **kwargs) -> Any:
    """安全调用 LLM（统一重试+日志+错误分类）。"""
    start = time.time()
    log_llm_event("llm_call_start", model=kwargs.get("model", "unknown"))
    for attempt in range(3):
        try:
            result = call_fn(**kwargs)
            elapsed = time.time() - start
            log_llm_event("llm_call_success", model=kwargs.get("model", "unknown"), elapsed_seconds=round(elapsed, 2), attempt=attempt + 1)
            return result
        except Exception as e:
            retryable = _is_retryable_error(e)
            if retryable and attempt < 2:
                wait = min(2 ** attempt * 2, 15)
                log_llm_event("llm_retry", model=kwargs.get("model", "unknown"), attempt=attempt + 1, wait_seconds=wait, error=str(e))
                time.sleep(wait)
            else:
                elapsed = time.time() - start
                log_llm_event("llm_call_failed", model=kwargs.get("model", "unknown"), elapsed_seconds=round(elapsed, 2), error=str(e))
                raise RuntimeError(_classify_error(e)) from e
    raise RuntimeError("LLM 调用失败（重试耗尽）")


def safe_non_streaming_call(client, model: str, messages: list, temperature: float = 0.3, max_tokens: int = 4096, **kwargs) -> str:
    """安全的非流式 LLM 调用（统一重试+日志+错误分类），返回 content 字符串。"""
    start = time.time()
    log_llm_event("llm_call_start", model=model)
    for attempt in range(3):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                **kwargs,
            )
            content = response.choices[0].message.content or ""
            elapsed = time.time() - start
            log_llm_event("llm_call_success", model=model, elapsed_seconds=round(elapsed, 2), attempt=attempt + 1)
            return content
        except Exception as e:
            retryable = _is_retryable_error(e)
            if retryable and attempt < 2:
                wait = min(2 ** attempt * 2, 15)
                log_llm_event("llm_retry", model=model, attempt=attempt + 1, wait_seconds=wait, error=str(e))
                time.sleep(wait)
            else:
                elapsed = time.time() - start
                log_llm_event("llm_call_failed", model=model, elapsed_seconds=round(elapsed, 2), error=str(e))
                raise RuntimeError(_classify_error(e)) from e
    raise RuntimeError("LLM 调用失败（重试耗尽）")


async def safe_async_non_streaming_call(client, model: str, messages: list, temperature: float = 0.3, max_tokens: int = 4096, **kwargs) -> str:
    """可取消的非流式调用；协程取消不会被错误分类或重试吞掉。"""
    start = time.time()
    log_llm_event("llm_call_start", model=model, async_call=True)
    for attempt in range(3):
        try:
            response = await client.chat.completions.create(
                model=model,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                **kwargs,
            )
            content = response.choices[0].message.content or ""
            elapsed = time.time() - start
            log_llm_event("llm_call_success", model=model, elapsed_seconds=round(elapsed, 2), attempt=attempt + 1, async_call=True)
            return content
        except Exception as e:
            retryable = _is_retryable_error(e)
            if retryable and attempt < 2:
                wait = min(2 ** attempt * 2, 15)
                log_llm_event("llm_retry", model=model, attempt=attempt + 1, wait_seconds=wait, error=str(e), async_call=True)
                await asyncio.sleep(wait)
            else:
                elapsed = time.time() - start
                log_llm_event("llm_call_failed", model=model, elapsed_seconds=round(elapsed, 2), error=str(e), async_call=True)
                raise RuntimeError(_classify_error(e)) from e
    raise RuntimeError("LLM 调用失败（重试耗尽）")


def safe_streaming_call(client, model: str, messages: list, temperature: float = 0.3, max_tokens: int = 4096, **kwargs):
    """安全的流式 LLM 调用（统一重试+日志+错误分类），返回流式响应对象。

    用法与 safe_non_streaming_call 一致，但会自动设置 stream=True，
    返回可直接迭代的流式响应（常用于 SSE 场景）。
    """
    start = time.time()
    log_llm_event("llm_call_start", model=model, stream=True)
    for attempt in range(3):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                stream=True,
                **kwargs,
            )
            elapsed = time.time() - start
            log_llm_event("llm_call_success", model=model, elapsed_seconds=round(elapsed, 2), attempt=attempt + 1, stream=True)
            return response
        except Exception as e:
            retryable = _is_retryable_error(e)
            if retryable and attempt < 2:
                wait = min(2 ** attempt * 2, 15)
                log_llm_event("llm_retry", model=model, attempt=attempt + 1, wait_seconds=wait, error=str(e), stream=True)
                time.sleep(wait)
            else:
                elapsed = time.time() - start
                log_llm_event("llm_call_failed", model=model, elapsed_seconds=round(elapsed, 2), error=str(e), stream=True)
                raise RuntimeError(_classify_error(e)) from e
    raise RuntimeError("LLM 调用失败（重试耗尽）")
