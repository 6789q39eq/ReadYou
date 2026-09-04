#!/usr/bin/env bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

if command -v apk >/dev/null 2>&1; then
  sudo apk add --no-cache git-lfs py3-pip
elif command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq git-lfs python3-pip python3-venv
else
  echo "Neither apk nor apt-get is available; cannot install Codespace dependencies." >&2
  exit 1
fi

python3 -m pip install --user --break-system-packages --quiet pipx
python3 -m pipx ensurepath
python3 -m pipx install --force mcp-server-fetch
python3 -m pipx install --force duckduckgo-mcp-server
