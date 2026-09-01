"""流式 Unicode 健壮性测试。"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from backend.app.services.output_postprocess import _normalize_replacement
from backend.app.services.chat_service import _consume_stream_delta, _repair_unicode_text, sse_event


def test_consume_stream_delta_reassembles_split_emoji():
    first, carry = _consume_stream_delta("\ud83d", "")
    second, carry = _consume_stream_delta("\ude00。", carry)

    assert first == ""
    assert second == "😀。"
    assert carry == ""


def test_repair_unicode_text_replaces_unpaired_surrogate():
    assert _repair_unicode_text("\ud83d") == "�"


def test_sse_event_serializes_payload_with_broken_surrogate():
    payload = {"type": "delta", "delta": "\ud83d"}
    event = sse_event(payload)

    assert event.startswith("data: ")
    assert "�" in event


def test_normalize_replacement_supports_dollar_group_syntax():
    replacement = _normalize_replacement("$1-$2")

    assert replacement == r"\g<1>-\g<2>"
