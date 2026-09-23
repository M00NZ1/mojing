import asyncio

import httpx
import pytest

from backend.app.services.model_price_discovery import PriceUnavailable, discover_model_price, parse_explicit_price


def test_explicit_price_requires_currency_and_unit():
    model = {"pricing": {"input_per_million": "2.5", "output_per_million": 10, "currency": "CNY"}}
    assert parse_explicit_price(model, "api.example.com") == {
        "currency": "CNY", "input_per_million": 2.5,
        "output_per_million": 10.0, "cached_input_per_million": 0.0,
    }
    with pytest.raises(PriceUnavailable):
        parse_explicit_price({"pricing": {"prompt": "0.001", "completion": "0.002"}}, "api.example.com")
    with pytest.raises(PriceUnavailable):
        parse_explicit_price({"pricing": {"input_per_million": "nan", "output_per_million": 1, "currency": "USD"}}, "api.example.com")


def test_discovery_uses_exact_model_and_pagination_without_saving():
    requested = []

    def handle(request: httpx.Request):
        requested.append((str(request.url), request.headers.get("authorization")))
        if "after=first" not in str(request.url):
            return httpx.Response(200, json={"data": [{"id": "other"}], "has_more": True, "last_id": "first"})
        return httpx.Response(200, json={"data": [{"id": "target", "pricing": {
            "currency": "USD", "unit": "per_token", "prompt": "0.000002", "completion": "0.000004",
        }}], "has_more": False})

    price = asyncio.run(discover_model_price("https://example.com/v1", "test-key", "target", transport=httpx.MockTransport(handle)))
    assert price["input_per_million"] == 2.0
    assert price["output_per_million"] == 4.0
    assert len(requested) == 2
    assert all(authorization == "Bearer test-key" for _, authorization in requested)


def test_missing_explicit_price_keeps_manual_entry_available():
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
        "data": [{"id": "target", "pricing": {"prompt": "0.1", "completion": "0.2"}}], "has_more": False,
    }))
    with pytest.raises(PriceUnavailable):
        asyncio.run(discover_model_price("https://example.com/v1", "test-key", "target", transport=transport))
