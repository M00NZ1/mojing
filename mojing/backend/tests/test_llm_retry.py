from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import openai
import pytest

from backend.app.services import llm_retry


class _FailingCompletions:
    def __init__(self, error: Exception) -> None:
        self.error = error
        self.calls = 0

    def create(self, **_kwargs):
        self.calls += 1
        raise self.error


def _client_for(error: Exception):
    completions = _FailingCompletions(error)
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    return client, completions


def _status_error(error_type, status_code: int):
    request = httpx.Request('POST', 'https://example.invalid/v1/chat/completions')
    response = httpx.Response(status_code, request=request)
    return error_type('verification error', response=response, body={'error': 'verification'})


@pytest.mark.parametrize(
    ('error_type', 'status_code', 'message'),
    [
        (openai.BadRequestError, 400, '请求参数错误'),
        (openai.AuthenticationError, 401, 'API Key 认证失败'),
        (openai.PermissionDeniedError, 403, '接口拒绝访问'),
        (openai.NotFoundError, 404, '找不到指定的模型或接口'),
        (openai.UnprocessableEntityError, 422, '请求参数错误'),
    ],
)
def test_streaming_call_does_not_retry_non_retryable_status(
    monkeypatch: pytest.MonkeyPatch,
    error_type,
    status_code: int,
    message: str,
) -> None:
    client, completions = _client_for(_status_error(error_type, status_code))
    waits: list[float] = []
    monkeypatch.setattr(llm_retry.time, 'sleep', waits.append)

    with pytest.raises(RuntimeError, match=message):
        llm_retry.safe_streaming_call(client, model='test-model', messages=[])

    assert completions.calls == 1
    assert waits == []


def test_non_streaming_call_does_not_retry_bad_request(monkeypatch: pytest.MonkeyPatch) -> None:
    client, completions = _client_for(_status_error(openai.BadRequestError, 400))
    waits: list[float] = []
    monkeypatch.setattr(llm_retry.time, 'sleep', waits.append)

    with pytest.raises(RuntimeError, match='请求参数错误'):
        llm_retry.safe_non_streaming_call(client, model='test-model', messages=[])

    assert completions.calls == 1
    assert waits == []


def test_streaming_call_retries_rate_limit(monkeypatch: pytest.MonkeyPatch) -> None:
    client, completions = _client_for(_status_error(openai.RateLimitError, 429))
    waits: list[float] = []
    monkeypatch.setattr(llm_retry.time, 'sleep', waits.append)

    with pytest.raises(RuntimeError, match='请求频率超限'):
        llm_retry.safe_streaming_call(client, model='test-model', messages=[])

    assert completions.calls == 3
    assert waits == [2, 4]


def test_async_non_streaming_call_propagates_cancellation_without_retry() -> None:
    class BlockingAsyncCompletions:
        def __init__(self) -> None:
            self.calls = 0
            self.started = asyncio.Event()
            self.cancelled = False

        async def create(self, **_kwargs):
            self.calls += 1
            self.started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled = True
                raise

    async def scenario() -> None:
        completions = BlockingAsyncCompletions()
        client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
        task = asyncio.create_task(
            llm_retry.safe_async_non_streaming_call(client, model='test-model', messages=[])
        )
        await completions.started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert completions.calls == 1
        assert completions.cancelled is True

    asyncio.run(scenario())
