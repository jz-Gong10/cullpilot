#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${CULLPILOT_ROOT_DIR:-/root/workspace/aic}"
MODEL_ROOT="${ANALYSIS_MODEL_ROOT:-$ROOT_DIR/demo/models}"
MANIFEST="${CULLPILOT_MODEL_MANIFEST:-$MODEL_ROOT/model_manifest.json}"

if [[ ! -f "$MANIFEST" ]]; then
  echo "Model manifest does not exist: $MANIFEST" >&2
  exit 1
fi

python3 - "$MANIFEST" "$MODEL_ROOT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
model_root = Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
failures = []

for item in manifest["files"]:
    path = model_root / item["path"]
    if not path.is_file():
        failures.append(f"missing: {path}")
        continue
    size = path.stat().st_size
    if size != item["size"]:
        failures.append(f"size: {path} ({size} != {item['size']})")
        continue
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != item["sha256"]:
        failures.append(f"sha256: {path}")

if failures:
    print("Model verification failed:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    raise SystemExit(1)

print(f"Verified {len(manifest['files'])} model files under {model_root}")
PY
