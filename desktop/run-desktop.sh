#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(dirname "$SCRIPT_DIR")
export PYTHONPATH="$SCRIPT_DIR${PYTHONPATH:+:$PYTHONPATH}"
cd "$PROJECT_ROOT"
PYTHON="$PROJECT_ROOT/.desktop-venv/bin/python"
if [ ! -x "$PYTHON" ]; then PYTHON=python3; fi
exec "$PYTHON" -m archipelago_companion
