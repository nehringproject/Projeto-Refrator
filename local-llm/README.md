# Local GGUF runtime

This module adapts Arm's MIT-licensed AI Chat Android wrapper and links it to the
pinned `llama.cpp` submodule. Refrator-specific changes include model metadata
validation, runtime sizing, tool-call streaming and Android process integration.

Upstream projects:

- https://github.com/ARM/ai-chat
- https://github.com/ggml-org/llama.cpp

See the root `NOTICE` and `THIRD_PARTY_NOTICES.md` files for attribution.
