#!/usr/bin/env bash
# Install GitHub Copilot CLI for Codespace shells, including SSH sessions.
set -euo pipefail

INSTALL_DIR="${HOME}/.local/bin"
mkdir -p "${INSTALL_DIR}"

copilot_works() {
  command -v copilot >/dev/null 2>&1 && copilot --version >/dev/null 2>&1
}

if ! copilot_works; then
  # The native installer targets glibc and cannot run in Alpine-based containers.
  if command -v apk >/dev/null 2>&1; then
    sudo apk add --no-cache nodejs npm
    rm -f "${INSTALL_DIR}/copilot"
    npm install --global --prefix "${HOME}/.local" @github/copilot
  else
    curl -fsSL https://gh.io/copilot-install | PREFIX="${HOME}/.local" bash
  fi
fi

if ! grep -q "codespace-copilot-cli" "${HOME}/.bashrc" 2>/dev/null; then
  cat >> "${HOME}/.bashrc" <<'EOF'

# codespace-copilot-cli: GitHub Copilot CLI
export PATH="${HOME}/.local/bin:${PATH}"
EOF
fi

if ! copilot_works; then
  echo "Copilot CLI installation did not provide a copilot executable." >&2
  exit 1
fi

echo "GitHub Copilot CLI is ready: ${INSTALL_DIR}/copilot"
