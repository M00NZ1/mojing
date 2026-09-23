"""Read explicit model prices from an OpenAI-compatible model catalog."""

from __future__ import annotations

import asyncio
import math
from urllib.parse import urlsplit

import httpx


class PriceUnavailable(Exception):
    """The provider did not publish an unambiguous price for this model."""


class PriceDiscoveryError(Exception):
    """The provider catalog could not be read."""


def _amount(value) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number >= 0 else None


def parse_explicit_price(model: dict, host: str) -> dict:
    """Accept only a stated currency/unit, or OpenRouter's documented per-token fields."""
    pricing = model.get("pricing")
    if not isinstance(pricing, dict):
        raise PriceUnavailable()
    currency = str(pricing.get("currency") or model.get("currency") or "").upper()
    unit = str(pricing.get("unit") or model.get("unit") or "").lower()
    million_units = {"per_million", "per_million_tokens", "million_tokens"}
    prompt = _amount(pricing.get("input_per_million", pricing.get("inputPerMillion")))
    completion = _amount(pricing.get("output_per_million", pricing.get("outputPerMillion")))
    cached = _amount(pricing.get("cached_input_per_million", pricing.get("cachedInputPerMillion")))
    if currency in {"USD", "CNY"} and (not unit or unit in million_units) and prompt is not None and completion is not None:
        return {"currency": currency, "input_per_million": prompt,
                "output_per_million": completion, "cached_input_per_million": cached or 0.0}
    prompt = _amount(pricing.get("prompt"))
    completion = _amount(pricing.get("completion"))
    openrouter = host == "openrouter.ai" or host.endswith(".openrouter.ai")
    if prompt is not None and completion is not None and (
        currency in {"USD", "CNY"} and unit in {"token", "per_token"}
        or openrouter and not currency and not unit
    ):
        return {"currency": currency or "USD", "input_per_million": prompt * 1_000_000,
                "output_per_million": completion * 1_000_000, "cached_input_per_million": 0.0}
    raise PriceUnavailable()


async def discover_model_price(base_url: str, api_key: str, model_name: str, *, transport=None) -> dict:
    """Find one exact model in the provider's paginated catalog without saving a price."""
    from .openai_compatible_routing import normalize_base, uses_versioned_root

    root = normalize_base(base_url)
    if not uses_versioned_root(root):
        root += "/v1"
    host = (urlsplit(root).hostname or "").lower()
    anthropic = host == "api.anthropic.com"
    headers = {"x-api-key": api_key, "anthropic-version": "2023-06-01"} if anthropic else {"Authorization": f"Bearer {api_key}"}
    params: dict[str, str] = {}
    cursors: set[str] = set()
    try:
        async with asyncio.timeout(60), httpx.AsyncClient(timeout=20, follow_redirects=False, transport=transport) as client:
            for _ in range(100):
                response = await client.get(f"{root}/models", headers=headers, params=params)
                if response.status_code != 200:
                    raise PriceDiscoveryError(f"平台模型接口返回 HTTP {response.status_code}")
                data = response.json()
                if not isinstance(data, dict) or not isinstance(data.get("data"), list):
                    raise PriceDiscoveryError("平台模型目录格式无法读取")
                for item in data["data"]:
                    if isinstance(item, dict) and item.get("id") == model_name:
                        return parse_explicit_price(item, host)
                if not data.get("has_more"):
                    raise PriceUnavailable()
                cursor = data.get("last_id")
                if not isinstance(cursor, str) or not cursor or cursor in cursors:
                    raise PriceDiscoveryError("平台模型目录分页格式无法读取")
                cursors.add(cursor)
                params = {"after_id" if anthropic else "after": cursor}
    except (httpx.HTTPError, TimeoutError) as exc:
        raise PriceDiscoveryError("平台连接中断或超时") from exc
    except (ValueError, TypeError) as exc:
        raise PriceDiscoveryError("平台模型目录格式无法读取") from exc
    raise PriceDiscoveryError("平台模型目录分页未结束")
