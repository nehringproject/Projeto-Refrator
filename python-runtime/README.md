# Embedded Python runtime

Chaquopy packages CPython and the pinned requirements declared in
`build.gradle.kts`. LiteLLM runs through its Router API in an isolated Android
process; the administrative proxy stack is not exposed.

The `*-android` directories are narrow compatibility shims for native Python
packages which do not publish Android wheels. Their local version suffix and
package metadata identify them as Refrator builds. They implement only the APIs
needed by the pinned runtime and must not be treated as general replacements for
the upstream packages.

The two Pydantic Core wheels are reproducible Android cross-builds. Changes to
Python packages require a dependency, license and import-surface review.
