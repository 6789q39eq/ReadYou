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
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "${JAVA_BIN}" ]; then
  if command -v apk >/dev/null 2>&1; then
    sudo apk add --no-cache openjdk17 unzip wget github-cli
    JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
  elif command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-17-jdk unzip wget gh
    JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
  else
    echo "Neither apk nor apt-get is available; cannot install Java and build helpers." >&2
    exit 1
  fi
fi
export JAVA_HOME

# 2. Configure Git to use the Codespace/GitHub CLI credential instead of
#    prompting for an HTTPS username and password in `gh cs ssh` sessions.
if command -v gh >/dev/null 2>&1; then
  git config --global --replace-all credential.helper '!gh auth git-credential'
fi

# 3. Android SDK command-line tools (idempotent).
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

# 4. Licenses + pinned platform packages (matches app/compileSdk = 36).
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# 5. Gradle settings for small codespace VMs (kept out of the repo).
#    Validated on an 8GB box: the Gradle daemon is SIGKilled even at -Xmx2g,
#    so it stays off (equivalent of --no-daemon) and every JVM is capped.
#    Without this, builds die with "Gradle build daemon disappeared
#    unexpectedly" before any task runs.
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "${HOME}/.gradle"
if ! grep -q "codespace-android" "${HOME}/.gradle/gradle.properties" 2>/dev/null; then
  if [ -f "${HOME}/.gradle/gradle.properties" ]; then
    cp "${HOME}/.gradle/gradle.properties" "${HOME}/.gradle/gradle.properties.bak"
  fi
  cat > "${HOME}/.gradle/gradle.properties" <<'EOF'
# codespace-android: lean defaults, see .devcontainer/setup-android.sh
org.gradle.daemon=false
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.workers.max=2
kotlin.daemon.jvmargs=-Xmx1g
EOF
fi

# 6. Point Gradle at the SDK even in shells that never inherit the
#    devcontainer remoteEnv (non-interactive shells, agent tool shells):
#    local.properties is gitignored, so this is per-machine only.
if ! grep -q "^sdk\.dir=${SDK_ROOT//\//\\/}$" "${REPO_DIR}/local.properties" 2>/dev/null; then
  if [ -f "${REPO_DIR}/local.properties" ]; then
    grep -v "^sdk\.dir=" "${REPO_DIR}/local.properties" > "${REPO_DIR}/local.properties.tmp" || true
    mv "${REPO_DIR}/local.properties.tmp" "${REPO_DIR}/local.properties"
  fi
  echo "sdk.dir=${SDK_ROOT}" >> "${REPO_DIR}/local.properties"
fi

# 7. Persist the SDK env vars for every interactive shell, so sdkmanager,
#    adb and Gradle Just Work in fresh terminals without remoteEnv.
if ! grep -q "codespace-android-sdk" "${HOME}/.bashrc" 2>/dev/null; then
  cat >> "${HOME}/.bashrc" <<EOF

# codespace-android-sdk: Android toolchain, see .devcontainer/setup-android.sh
export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export JAVA_HOME="${JAVA_HOME}"
export PATH="${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:\$PATH"
EOF
fi

echo "Android SDK ready at ${SDK_ROOT}"
