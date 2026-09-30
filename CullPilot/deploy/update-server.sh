#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${CULLPILOT_APP_DIR:-/srv/cullpilot/app}"
BRANCH="${CULLPILOT_GIT_BRANCH:-main}"
VENV_DIR="${CULLPILOT_VENV_DIR:-/srv/cullpilot/venv}"
PYTHON_BIN="${CULLPILOT_PYTHON_BIN:-python3.12}"

if [[ ! -d "$APP_DIR/.git" ]]; then
  echo "The server checkout does not exist: $APP_DIR" >&2
  exit 1
fi

git -C "$APP_DIR" fetch --prune origin "$BRANCH"
git -C "$APP_DIR" checkout --force -B "$BRANCH" "origin/$BRANCH"

mkdir -p "$(dirname "$VENV_DIR")" /srv/cullpilot/data /srv/cullpilot/storage
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

"$VENV_DIR/bin/python" -m pip install --upgrade pip
"$VENV_DIR/bin/python" -m pip install -r "$APP_DIR/CullPilot/python-api/requirements-ai.txt"

(
  cd "$APP_DIR/CullPilot/backend-java"
  mvn -B -DskipTests package
)

sudo systemctl daemon-reload
sudo systemctl restart cullpilot-python.service
sudo systemctl restart cullpilot-java.service
sudo systemctl --no-pager --full status cullpilot-python.service cullpilot-java.service
