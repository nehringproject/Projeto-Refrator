"""Small compatibility surface used by LiteLLM when Rust tokenizers is unavailable."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class Encoding:
    ids: list[int]


class Tokenizer:
    @classmethod
    def from_str(cls, _serialized: str) -> "Tokenizer":
        return cls()

    @classmethod
    def from_file(cls, _path: str) -> "Tokenizer":
        return cls()

    @classmethod
    def from_pretrained(cls, _identifier: str, **_kwargs) -> "Tokenizer":
        return cls()

    def encode(self, text: str, **_kwargs) -> Encoding:
        return Encoding(list(text.encode("utf-8")))

    def encode_batch(self, texts: Iterable[str], **_kwargs) -> list[Encoding]:
        return [self.encode(text) for text in texts]

    def decode(
        self,
        tokens: Iterable[int],
        skip_special_tokens: bool = True,
        **_kwargs,
    ) -> str:
        del skip_special_tokens
        return bytes(int(token) & 0xFF for token in tokens).decode("utf-8", errors="replace")

    def decode_batch(self, token_batches: Iterable[Iterable[int]], **kwargs) -> list[str]:
        return [self.decode(tokens, **kwargs) for tokens in token_batches]

    def get_added_tokens_decoder(self) -> dict[int, object]:
        return {}


__all__ = ["Encoding", "Tokenizer"]
