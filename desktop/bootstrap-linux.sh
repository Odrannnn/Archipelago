#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(dirname "$SCRIPT_DIR")
python3 -m venv "$PROJECT_ROOT/.desktop-venv"
"$PROJECT_ROOT/.desktop-venv/bin/python" -m pip install --upgrade pip
"$PROJECT_ROOT/.desktop-venv/bin/python" -m pip install -r "$PROJECT_ROOT/requirements.txt" -r "$SCRIPT_DIR/requirements.txt"
printf '%s\n' 'Desktop Companion is ready. Run ./desktop/run-desktop.sh'

