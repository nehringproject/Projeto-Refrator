# Contributing

Open an issue before large architectural work. Keep changes focused and do not
include credentials, private conversations, device identifiers or signing
material.

Clone submodules and run the public checks before submitting a change:

```powershell
git submodule update --init --recursive
./gradlew.bat :core:test :provider-http:test :app:testPublicDebugUnitTest
./gradlew.bat :app:lintPublicDebug :app:assemblePublicDebug
./scripts/audit-public-source.ps1
```

Changes to tools or Android capabilities must document their permission,
side-effect and recovery boundaries. Changes to the embedded runtime require a
new checksum, package inventory, license review and corresponding-source bundle.

By submitting a contribution, you agree that it is licensed under the repository
license, unless the file is clearly identified as third-party material under
another license.
