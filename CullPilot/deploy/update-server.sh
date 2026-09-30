#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this deployment script as root." >&2
  exit 1
fi

ROOT_DIR="${CULLPILOT_ROOT_DIR:-/root/workspace/aic}"
RELEASE_ID="${CULLPILOT_RELEASE_ID:-}"
ARCHIVE="${CULLPILOT_RELEASE_ARCHIVE:-}"
RELEASES_DIR="$ROOT_DIR/releases"
CURRENT_LINK="$ROOT_DIR/current"
VENV_DIR="${CULLPILOT_VENV_DIR:-$ROOT_DIR/runtime/venv}"
PYTHON_BIN="${CULLPILOT_PYTHON_BIN:-/root/miniconda3/envs/aic/bin/python3.10}"
ENV_FILE="${CULLPILOT_ENV_FILE:-/etc/cullpilot/cullpilot.env}"

if [[ -z "$RELEASE_ID" || ! "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "A valid CULLPILOT_RELEASE_ID is required." >&2
  exit 1
fi
if [[ -z "$ARCHIVE" || ! -f "$ARCHIVE" ]]; then
  echo "The deployment archive does not exist: $ARCHIVE" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Create and review it before deploying." >&2
  exit 1
fi
if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Python executable does not exist: $PYTHON_BIN" >&2
  exit 1
fi
if [[ ! -f "$ROOT_DIR/CullPilot/data/cullpilot.db" ]]; then
  echo "Existing database is missing; refusing to create a new one." >&2
  exit 1
fi
for protected_dir in "$ROOT_DIR/demo/models" "$ROOT_DIR/demo/data" "$ROOT_DIR/CullPilot/storage"; do
  if [[ ! -d "$protected_dir" ]]; then
    echo "Protected directory is missing: $protected_dir" >&2
    exit 1
  fi
done
if [[ ! -f "$ROOT_DIR/demo/models/model_manifest.json" ]]; then
  echo "Model manifest is missing; verify the server model directory." >&2
  exit 1
fi
if [[ -e "$CURRENT_LINK" && ! -L "$CURRENT_LINK" ]]; then
  echo "$CURRENT_LINK exists and is not a symbolic link; refusing to replace it." >&2
  exit 1
fi

mkdir -p "$RELEASES_DIR" "$ROOT_DIR/runtime"

RELEASE_DIR="$RELEASES_DIR/$RELEASE_ID"
if [[ -e "$RELEASE_DIR" ]]; then
  echo "Release already exists: $RELEASE_DIR" >&2
  exit 1
fi
mkdir "$RELEASE_DIR"
tar --extract --gzip --file "$ARCHIVE" --directory "$RELEASE_DIR" \
  --no-same-owner --no-same-permissions

test -f "$RELEASE_DIR/CullPilot/python-api/app/main.py"
test -f "$RELEASE_DIR/CullPilot/python-api/requirements-ai.txt"
test -f "$RELEASE_DIR/CullPilot/backend-java/target/cullpilot-backend-0.0.1-SNAPSHOT.jar"
test -f "$ROOT_DIR/demo/models/model_manifest.json"

BACKUP_DIR="$ROOT_DIR/backups/$(date +%Y%m%d_%H%M%S)_$RELEASE_ID"
mkdir -p "$BACKUP_DIR"
"$PYTHON_BIN" - "$ROOT_DIR/CullPilot/data/cullpilot.db" "$BACKUP_DIR/cullpilot.db" <<'PY'
import sqlite3
import sys

source, target = sys.argv[1:]
with sqlite3.connect(f"file:{source}?mode=ro", uri=True) as source_db, sqlite3.connect(target) as target_db:
    source_db.backup(target_db)
PY
tar --create --gzip --file "$BACKUP_DIR/cullpilot-storage.tar.gz" \
  --directory "$ROOT_DIR/CullPilot" storage
tar --create --gzip --file "$BACKUP_DIR/demo-data.tar.gz" \
  --directory "$ROOT_DIR/demo" data
echo "Created deployment backup: $BACKUP_DIR"

if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  mkdir -p "$(dirname "$VENV_DIR")"
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

export PIP_NO_CACHE_DIR=1
"$VENV_DIR/bin/python" -m pip install --upgrade pip
"$VENV_DIR/bin/python" -m pip install torch==2.5.1 \
  --index-url https://download.pytorch.org/whl/cpu
"$VENV_DIR/bin/python" -m pip install -r "$RELEASE_DIR/CullPilot/python-api/requirements-ai.txt"

previous_release=""
if [[ -L "$CURRENT_LINK" ]]; then
  previous_release="$(readlink -f "$CURRENT_LINK" || true)"
fi
next_link="$ROOT_DIR/.current-$RELEASE_ID"
ln -s "$RELEASE_DIR" "$next_link"
mv -T "$next_link" "$CURRENT_LINK"

install -m 0644 "$RELEASE_DIR/CullPilot/deploy/systemd/cullpilot-python.service" \
  /etc/systemd/system/cullpilot-python.service
install -m 0644 "$RELEASE_DIR/CullPilot/deploy/systemd/cullpilot-java.service" \
  /etc/systemd/system/cullpilot-java.service

systemctl daemon-reload
systemctl stop cullpilot-java.service cullpilot-python.service 2>/dev/null || true

# The first migration may find older processes that were started outside systemd.
# Stop only the known CullPilot commands so their ports can be claimed safely.
for pattern in \
  'python -m uvicorn app.main:app --host 127.0.0.1 --port 8001' \
  'java -jar target/cullpilot-backend-0.0.1-SNAPSHOT.jar --server.port=8080'; do
  while read -r pid _; do
    [[ -z "$pid" || "$pid" == "$$" ]] && continue
    command_line="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    if [[ "$command_line" == *"$pattern"* ]]; then
      echo "Stopping legacy CullPilot process $pid"
      kill "$pid" 2>/dev/null || true
    fi
  done < <(pgrep -af "$pattern" || true)
done
sleep 2

systemctl enable cullpilot-python.service cullpilot-java.service >/dev/null
systemctl restart cullpilot-python.service

for attempt in {1..30}; do
  if systemctl is-active --quiet cullpilot-python.service && \
      curl --fail --silent --max-time 3 http://127.0.0.1:8001/api/v1/health/models >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    journalctl -u cullpilot-python.service -n 80 --no-pager >&2 || true
    exit 1
  fi
  sleep 2
done

systemctl restart cullpilot-java.service
for attempt in {1..30}; do
  if systemctl is-active --quiet cullpilot-java.service && \
      curl --fail --silent --max-time 3 http://127.0.0.1:8080/api/v1/health >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    journalctl -u cullpilot-java.service -n 80 --no-pager >&2 || true
    exit 1
  fi
  sleep 2
done

bash "$RELEASE_DIR/CullPilot/deploy/verify-models.sh"
echo "Python model health:"
curl --fail --silent --show-error http://127.0.0.1:8001/api/v1/health/models
echo

systemctl --no-pager --full status cullpilot-python.service cullpilot-java.service

echo "Activated release: $RELEASE_DIR"
if [[ -n "$previous_release" ]]; then
  echo "Previous release remains at: $previous_release"
fi
