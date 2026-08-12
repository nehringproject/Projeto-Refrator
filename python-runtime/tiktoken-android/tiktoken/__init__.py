"""Conservative, reversible tokenizer used when the Rust tiktoken wheel is unavailable.

Each UTF-8 byte is represented as one token. This is not model-accurate and is intentionally
conservative for context guards: typical BPE tokenizers combine several bytes into one token.
"""

from __future__ import annotations

from collections.abc import Collection, Iterable


class Encoding:
    def __init__(self, name: str = "android_byte_fallback", **_ignored):
        self.name = name
        self.n_vocab = 256
        self.eot_token = 0
        self.special_tokens_set: set[str] = set()

    def encode(
        self,
        text: str,
        *,
        allowed_special: str | Collection[str] = set(),
        disallowed_special: str | Collection[str] = "all",
    ) -> list[int]:
        del allowed_special, disallowed_special
        return list(text.encode("utf-8"))

    def encode_ordinary(self, text: str) -> list[int]:
        return self.encode(text, disallowed_special=())

    def decode(self, tokens: Iterable[int], errors: str = "replace") -> str:
        return bytes(int(token) & 0xFF for token in tokens).decode("utf-8", errors=errors)

    def decode_bytes(self, tokens: Iterable[int]) -> bytes:
        return bytes(int(token) & 0xFF for token in tokens)

    def encode_single_token(self, text_or_bytes: str | bytes) -> int:
        raw = text_or_bytes.encode("utf-8") if isinstance(text_or_bytes, str) else text_or_bytes
        if len(raw) != 1:
            raise KeyError("Android byte fallback accepts exactly one byte")
        return raw[0]

    def decode_single_token_bytes(self, token: int) -> bytes:
        return bytes([int(token) & 0xFF])


_ENCODINGS: dict[str, Encoding] = {}


def get_encoding(encoding_name: str) -> Encoding:
    return _ENCODINGS.setdefault(encoding_name, Encoding(encoding_name))


def encoding_for_model(model_name: str) -> Encoding:
    return get_encoding(f"android-byte:{model_name}")


def list_encoding_names() -> list[str]:
    return ["cl100k_base", "o200k_base", "p50k_base", "r50k_base"]


__all__ = ["Encoding", "encoding_for_model", "get_encoding", "list_encoding_names"]
