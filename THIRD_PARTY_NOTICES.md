# Third-party notices

The root `LICENSE` applies to original Refrator source code. It does not replace
the licenses of third-party components.

## Directly integrated components

| Component | Version or revision | License | Project |
| --- | --- | --- | --- |
| AndroidX and Jetpack Compose | version catalog | Apache-2.0 | https://github.com/androidx/androidx |
| Kotlin and kotlinx.coroutines | build configuration | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| Arm AI Chat-derived Android wrapper | adapted source | MIT | https://github.com/ARM/ai-chat |
| Chaquopy | 17.0.0 | MIT | https://github.com/chaquo/chaquopy |
| llama.cpp | `876a4321163249c43ca4e986818fab5ab081f282` | MIT | https://github.com/ggml-org/llama.cpp |
| LiteLLM | 1.90.6 | MIT | https://github.com/BerriAI/litellm |
| JGit | 7.6.0.202603022253-r | Eclipse Distribution License 1.0 | https://github.com/eclipse-jgit/jgit |
| Shizuku API and provider | 13.1.5 | MIT | https://github.com/RikkaApps/Shizuku-API |
| Tesseract OCR and tesseract4android | 5.x / 4.9.0 | Apache-2.0 | https://github.com/tesseract-ocr/tesseract and https://github.com/adaptech-cz/Tesseract4Android |

The Python process also contains CPython and packages resolved by
`python-runtime/build.gradle.kts`, including pip, Pydantic, PyYAML, packaging,
jsonschema and LiteLLM dependencies. Their metadata and license files in the
built Python distribution remain authoritative. The small Android compatibility
packages under `python-runtime/*-android` are part of Refrator and use the root
license; they are not upstream releases of similarly named projects.

## Embedded command-line runtime

`runtime-assets/bootstrap-aarch64.zip` contains unmodified Termux packages with
multiple licenses, including GPL and LGPL families. Its installed package
database is stored at `var/lib/dpkg/status`; package copyright and license files
are retained under `share/doc`.

Distributing an APK which embeds this archive requires corresponding source for
the exact binaries, including the Termux build recipes and modifications used.
See `runtime-assets/SOURCE_OFFER.md`. A release must not be published until that
gate is satisfied.

## Models and user downloads

Refrator does not bundle a GGUF model. Users are responsible for the license and
usage terms of models and packages they import or download.
