#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

ROOT_DIR="${CULLPILOT_ROOT_DIR:-/root/workspace/aic}"
CURRENT_DIR="${CULLPILOT_CURRENT_DIR:-$ROOT_DIR/current}"
RUNTIME_DIR="${CULLPILOT_RUNTIME_DIR:-$ROOT_DIR/runtime}"
ENV_FILE="${CULLPILOT_ENV_FILE:-/etc/cullpilot/cullpilot.env}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemd is required on the server." >&2
  exit 1
fi

if [[ ! -L "$CURRENT_DIR" || ! -d "$CURRENT_DIR" ]]; then
  echo "The current release link does not exist: $CURRENT_DIR" >&2
  exit 1
fi

install -d -m 0700 "$ROOT_DIR/releases" "$RUNTIME_DIR"
install -d -m 0750 "$ROOT_DIR/CullPilot/data" "$ROOT_DIR/CullPilot/storage" \
  "$ROOT_DIR/demo/data" "$ROOT_DIR/demo/models"
install -d -m 0750 /etc/cullpilot

if [[ ! -e "$ENV_FILE" ]]; then
  install -o root -g root -m 0600 \
    "$SCRIPT_DIR/server.env.example" "$ENV_FILE"
  echo "Created $ENV_FILE; review it before starting the services."
fi

install -o root -g root -m 0644 \
  "$SCRIPT_DIR/systemd/cullpilot-python.service" \
  /etc/systemd/system/cullpilot-python.service
install -o root -g root -m 0644 \
  "$SCRIPT_DIR/systemd/cullpilot-java.service" \
  /etc/systemd/system/cullpilot-java.service

systemctl daemon-reload
systemctl enable cullpilot-python.service cullpilot-java.service

cat <<EOF
CullPilot server directories are ready.

  current: $CURRENT_DIR
  runtime: $RUNTIME_DIR
  data:    $ROOT_DIR/CullPilot/data
  storage: $ROOT_DIR/CullPilot/storage
  models:  $ROOT_DIR/demo/models
  env:    $ENV_FILE

The GitHub Actions deployment supplies releases and the Python environment.
After reviewing the environment file, run:
  systemctl daemon-reload
  systemctl enable cullpilot-python.service cullpilot-java.service
EOF
