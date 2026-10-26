# Repository Guidelines

## Project Structure & Module Organization

Magpie 用量 is an Android e-ink client built with Kotlin and Jetpack Compose. Source lives in `app/src/main/java/com/jianyun/x2app/`: `ui/` contains screens and ViewModel state, `api/` handles requests, `data/` holds models/settings, and `vpn/` and `tailscale/` integrate networking. Resources and licenses live in `app/src/main/res/` and `assets/`. JVM tests use `app/src/test/`; device tests use `app/src/androidTest/`. `native/appnet/` contains the Go engine; `third_party/appnet/` records patches, provenance, and checksums. `docs/` contains the showcase website and screenshots.

## Build, Test, and Development Commands

Use JDK 17 or 21, Android SDK 34, and Build Tools 34.0.0. Configure `ANDROID_HOME` or local `local.properties`.

- `./gradlew :app:assembleDebug`: build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:installDebug`: install on a connected ARM device running Android 11+.
- `./gradlew :app:testDebugUnitTest :app:lintDebug`: run JVM tests and Android lint, matching CI checks.
- `./gradlew :app:connectedDebugAndroidTest`: run device tests; network integration cases require their configured endpoints.
- `./scripts/build-appnet.sh`: rebuild the bundled AAR and checksum after native changes. First prepare the pinned tools described in `third_party/appnet/README.md`.

Ordinary APK builds use the committed AAR without compiling Go. Native tests use `go test -race ./...` in a prepared module with the documented Tailscale patches applied; the unpatched dependency lacks `DialStrict`.

## Coding Style & Naming Conventions

Follow Kotlin official style with four-space indentation. Use PascalCase for types and composables, camelCase for functions/properties, and descriptive filenames such as `QuotasScreen.kt`. Format Go with `gofmt`. Android lint is configured; no dedicated Kotlin formatter is configured. Reuse `InkUi.kt` primitives and preserve the monochrome layout. Avoid redundant labels and descriptions.

## Testing Guidelines

Use JUnit 4, coroutine test utilities, and MockWebServer for JVM tests; AndroidJUnitRunner for instrumentation; Go's `testing` package for native tests. Name Kotlin files `*Test.kt` with descriptive camelCase methods, and Go files `*_test.go` with `Test...` functions. Add regression coverage for changed behavior; no numeric coverage threshold is configured. Check UI changes in portrait and landscape on ARM hardware.

## Commit & Pull Request Guidelines

History uses short imperative subjects, such as “Use two quota columns on portrait tablets.” Keep commits focused. PRs should describe behavior, link relevant issues, list verification, and include screenshots for UI changes. Pass CI build, tests, lint, and AAR checksum verification.

## Security & Agent Instructions

Never commit API keys, VPN credentials, `local.properties`, or `.release-signing/`. Preserve fail-closed networking: disconnected tunnels must not silently fall back to direct access. Prefer Chinese communication; use Context7 for API/framework documentation.
