# CLAUDE.md

Guidance for working in the **AI Chat Sandbox** repo (Android / Kotlin / Jetpack Compose).

## Project shape

- Android app, package `com.aichat.sandbox`. Source under `app/src/main/java/...`, unit tests under `app/src/test/java/...`.
- Gradle (Kotlin DSL), wrapper pinned to **Gradle 8.5**. AGP **8.2.2**, Kotlin **1.9.22**, `compileSdk`/`targetSdk` **34**, `minSdk` 26. DI via Hilt; Room for storage; Retrofit/OkHttp for networking.
- Multi-provider AI client: `data/remote/` has `ApiClient` (router) + per-provider adapters (`OpenAiAdapter`, `AnthropicAdapter`, `GeminiAdapter`). Model→provider→credentials resolution lives in `ApiProvider` (`data/model/ChatSettings.kt`) and `PreferencesManager.credentialsFor()`. Per-model API quirks live in `data/model/ModelCapabilities.kt` (single source of truth — extend it instead of scattering string checks).

## Building & testing (IMPORTANT: one-time Android SDK setup)

Fresh web/cloud containers have **no Android SDK**, so Gradle fails with
`SDK location not found`. The environment's network policy allows
`dl.google.com`, so install the command-line tools and the SDK packages
this project needs. Run this **once per container** (Java 21 and
`curl`/`unzip` are already present):

```bash
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -sSL -o /tmp/cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
rm -rf /tmp/cmdline-tools-extract "$ANDROID_HOME/cmdline-tools/latest"
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
mv /tmp/cmdline-tools-extract/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"

# Accept licenses + install exactly what compileSdk 34 needs
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Point Gradle at the SDK (local.properties is gitignored). After this,
# gradle commands work in any shell without exporting ANDROID_HOME.
echo "sdk.dir=$ANDROID_HOME" > "$(git rev-parse --show-toplevel)/local.properties"
```

Notes:
- The download is ~150 MB; the SDK packages add a few hundred MB. Allow a
  generous timeout (the `Bash` tool default is fine; bump it if needed).
- If the `commandlinetools-linux-*_latest.zip` filename ever 404s, the
  build number changes over time — but the version above is what worked.
- The SDK lives outside the repo (`$HOME/android-sdk`) and `local.properties`
  is gitignored, so nothing SDK-related gets committed.

Then run the usual tasks:

```bash
./gradlew :app:testDebugUnitTest --console=plain     # JVM unit tests
./gradlew :app:assembleDebug --console=plain          # build the APK
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.model.*"           # a subset
```

## Known pre-existing unit-test failures (NOT regressions)

`./gradlew :app:testDebugUnitTest` reports ~22 failures in
`NoteSvgExporterTest`, `NoteVectorDrawableExporterTest`, and
`NoteAiServiceTest`. They all throw `android.graphics.Color` /
`android.util.Log` **"not mocked"** errors — these tests touch real Android
framework classes that aren't stubbed in plain JVM unit tests. They fail on
a clean checkout too. Treat the suite as green if these are the only
failures; focus on the tests relevant to your change.
