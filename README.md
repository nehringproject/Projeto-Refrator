# Refrator

Refrator is an Android agent workspace with multi-provider chat, local GGUF
models, tools, persistent tasks, Python, LiteLLM and optional Shizuku support.

## Requirements

- Android 9 or newer
- JDK 21
- Android SDK 36, NDK 29 and CMake 3.31

## Build

```powershell
git submodule update --init --recursive
./gradlew.bat :app:assemblePublicDebug
```

Provider credentials and signing keys are supplied by the user and are not
included in this repository.

## License

Refrator source is available under the
[PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use requires a
separate written license. Third-party components retain their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Commercial licensing: `nehringproject@gmail.com`
