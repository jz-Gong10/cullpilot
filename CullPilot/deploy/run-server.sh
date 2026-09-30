#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=/root/workspace/aic
RELEASE_ID="${CULLPILOT_RELEASE_ID:?CULLPILOT_RELEASE_ID is required}"
ARCHIVE="${CULLPILOT_RELEASE_ARCHIVE:?CULLPILOT_RELEASE_ARCHIVE is required}"

if [[ "$(id -u)" -ne 0 || ! "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "A root deployment and a valid release ID are required." >&2
  exit 1
fi

install -d -m 0700 "$ROOT_DIR/runtime/deploy-logs" "$ROOT_DIR/runtime/deploy-status"
LOG_FILE="$ROOT_DIR/runtime/deploy-logs/$RELEASE_ID.log"
STATUS_FILE="$ROOT_DIR/runtime/deploy-status/$RELEASE_ID.status"
exec >>"$LOG_FILE" 2>&1

if [[ -e "$STATUS_FILE" ]]; then
  echo "This deployment already completed: $RELEASE_ID"
  exit 1
fi

trap 'result=$?; printf "%s\n" "$result" > "$STATUS_FILE.tmp"; mv -T "$STATUS_FILE.tmp" "$STATUS_FILE"' EXIT
exec 9>"$ROOT_DIR/runtime/deploy.lock"
if ! flock -n 9; then
  echo "Another deployment is still running."
  exit 1
fi

echo "Starting release $RELEASE_ID"
CULLPILOT_ROOT_DIR="$ROOT_DIR" \
  CULLPILOT_RELEASE_ID="$RELEASE_ID" \
  CULLPILOT_RELEASE_ARCHIVE="$ARCHIVE" \
  bash "$ROOT_DIR/incoming/update-server-$RELEASE_ID.sh"
