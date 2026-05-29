#!/bin/bash
set -euo pipefail

# Only run in remote/web environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_HOME="$HOME/android-sdk"
SDK_MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
REPO_ROOT="$(git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --show-toplevel)"

# Install Android SDK if not already present
if [ ! -f "$SDK_MANAGER" ]; then
  echo "Installing Android command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sSL -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf /tmp/cmdline-tools-extract "$ANDROID_HOME/cmdline-tools/latest"
  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
  mv /tmp/cmdline-tools-extract/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi

# Install required SDK packages if not already present
if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
  echo "Installing Android SDK packages..."
  yes | "$SDK_MANAGER" --licenses >/dev/null 2>&1 || true
  "$SDK_MANAGER" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

# Write local.properties so Gradle can find the SDK
echo "sdk.dir=$ANDROID_HOME" > "$REPO_ROOT/local.properties"

# Persist ANDROID_HOME for the session
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$ANDROID_HOME" >> "$CLAUDE_ENV_FILE"
fi

echo "Android SDK ready at $ANDROID_HOME"
