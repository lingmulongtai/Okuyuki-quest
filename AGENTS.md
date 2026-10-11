# Development contract
- Follow the user's specification v0.3. Alpha scope is the first version, not v1.0.
- Preserve small, coherent Conventional Commits. Review staged changes before committing.
- Do not commit photos, secrets, local configuration, caches, or build artifacts.
- Run `./gradlew testDebugUnitTest :core:protocol:test lintDebug assembleRelease` before release.
- Physical Quest/Galaxy acceptance is separate from CI, JVM tests and emulator checks.
- Shared core sources are mirrored in the companion repository; update and verify both.
