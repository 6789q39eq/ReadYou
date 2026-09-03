#!/usr/bin/env bash
# Permanent Android build toolchain for GitHub Codespaces.
#
# Wired into .devcontainer/devcontainer.json (onCreateCommand), so every fresh
# codespace for this repo can build the app without manual setup.
# Idempotent: safe to re-run; skips steps that are already done.
set -euo pipefail

SDK_ROOT="/opt/android-sdk"
CMDLINE_VERSION="11076708"
CMDLINE_ZIP="commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
CMDLINE_URL="https://dl.google.com/android/repository/${CMDLINE_ZIP}"

export DEBIAN_FRONTEND=noninteractive

# 1. JDK 17 (required to run Gradle 8.x / AGP 8.x) + helpers + gh CLI
#    (gh publishes test APKs via .devcontainer/publish-apk.sh).
if [ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ] || ! command -v gh >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-17-jdk unzip wget gh
fi

# 2. Android SDK command-line tools (idempotent).
if [ ! -d "${SDK_ROOT}/cmdline-tools/latest" ]; then
  sudo mkdir -p "${SDK_ROOT}/cmdline-tools"
  if [ ! -f "/tmp/${CMDLINE_ZIP}" ]; then
    wget -q -O "/tmp/${CMDLINE_ZIP}" "${CMDLINE_URL}"
  fi
  sudo unzip -q -o "/tmp/${CMDLINE_ZIP}" -d "${SDK_ROOT}/cmdline-tools"
  sudo mv "${SDK_ROOT}/cmdline-tools/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
  rm -f "/tmp/${CMDLINE_ZIP}"
fi
sudo chown -R "$(id -u):$(id -g)" "${SDK_ROOT}"

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export PATH="${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:${PATH}"

# 3. Licenses + pinned platform packages (matches app/compileSdk = 36).
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# 4. Gradle memory settings for small codespace VMs (kept out of the repo).
mkdir -p "${HOME}/.gradle"
if ! grep -q "org.gradle.jvmargs" "${HOME}/.gradle/gradle.properties" 2>/dev/null; then
  printf 'org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\norg.gradle.caching=true\norg.gradle.workers.max=2\nkotlin.daemon.jvmargs=-Xmx1g\n' >> "${HOME}/.gradle/gradle.properties"
fi

echo "Android SDK ready at ${SDK_ROOT}"
