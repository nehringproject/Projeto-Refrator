"""Compatibility fallback for jiter's ``from_json`` entry point."""

from __future__ import annotations

import json
from typing import Any


def from_json(
    value: bytes | bytearray | memoryview | str,
    *,
    partial_mode: bool | str = False,
    **_kwargs,
) -> Any:
    text = bytes(value).decode("utf-8") if not isinstance(value, str) else value
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        if not partial_mode:
            raise
        # The OpenAI SDK only uses partial parsing to provide an early preview while a strict
        # JSON response streams. Returning None defers the preview; the complete payload is parsed
        # normally on the final chunk and never changes the provider response itself.
        return None


__all__ = ["from_json"]
