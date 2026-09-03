#!/usr/bin/env bash
# Build the githubDebug APK and publish it as a (pre)release on the fork.
#
# Permanent Codespaces workflow: this script lives in the repo, `gh` is
# installed by .devcontainer/setup-android.sh, and auth comes from the
# codespace's GITHUB_TOKEN -- nothing to set up by hand.
#
# Usage:
#   .devcontainer/publish-apk.sh [tag] [--draft] [--notes "text"] [--dry-run]
#
#   tag      Release tag. Default: test-apk-YYYYMMDD-HHMM.
#   --draft  Create as draft instead of prerelease.
#   --notes  Release notes. Default: build commit + filter-feature blurb.
#   --dry-run  Build only; skip the GitHub release step.
#
# Prints the download URL at the end.
set -euo pipefail

TAG="${1:-}"
DRAFT=false
NOTES=""
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --draft) DRAFT=true; shift ;;
    --notes) NOTES="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) shift ;;
  esac
done
if [[ -z "${TAG}" || "${TAG}" == -* ]]; then
  TAG="test-apk-$(date +%Y%m%d-%H%M)"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

# 1. Toolchain (uses the permanent setup if the SDK is missing).
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"
if [[ ! -d "${ANDROID_HOME}/platforms" ]]; then
  echo "Android SDK not found, running permanent setup first..."
  bash "${REPO_ROOT}/.devcontainer/setup-android.sh"
fi
if ! command -v gh >/dev/null 2>&1; then
  sudo apt-get update -qq && sudo apt-get install -y -qq gh
fi
if ! gh auth status >/dev/null 2>&1; then
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    echo "${GITHUB_TOKEN}" | gh auth login --with-token
  else
    echo "ERROR: gh is not authenticated and GITHUB_TOKEN is unset." >&2
    exit 1
  fi
fi
# Releases go to the fork (origin), never upstream.
REPO="$(git remote get-url origin)"

# 2. Build (--no-daemon: small codespace VMs SIGKILL the Gradle daemon).
./gradlew :app:assembleGithubDebug --no-daemon --console=plain

APK="$(ls -t app/build/outputs/apk/github/debug/*.apk | head -1)"
echo "Built: ${APK} ($(du -h "${APK}" | cut -f1))"

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "Dry run: skipping release creation."
  exit 0
fi

# 3. Publish.
HEAD_SHORT="$(git rev-parse --short HEAD)"
if [[ -z "${NOTES}" ]]; then
  NOTES="Debug-signed test build from main @ ${HEAD_SHORT}. Install on-device via the APK asset below (allow installs from unknown sources when asked)."
fi
FLAGS=(--prerelease --title "Test APK (${TAG})" --notes "${NOTES}")
if [[ "${DRAFT}" == "true" ]]; then
  FLAGS=(--draft --title "Test APK (${TAG})" --notes "${NOTES}")
fi
if gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1; then
  echo "Release ${TAG} exists, uploading asset to it..."
  gh release upload "${TAG}" --repo "${REPO}" --clobber "${APK}"
else
  gh release create "${TAG}" --repo "${REPO}" "${FLAGS[@]}" "${APK}"
fi

echo ""
echo "Download URL:"
echo "https://github.com/$(gh repo view "${REPO}" --json nameWithOwner -q .nameWithOwner)/releases/download/${TAG}/$(basename "${APK}")"
