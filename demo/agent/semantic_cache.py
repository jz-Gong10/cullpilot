"""Small on-disk embedding cache used by the CPU semantic features."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Optional

import numpy as np


ROOT = Path(__file__).resolve().parents[1]


def _cache_root() -> Path:
    """Resolve the cache root at use time so service/test storage can change."""
    configured_data_root = os.getenv("PHOTO_SCREENER_DATA_ROOT", "").strip()
    return (Path(configured_data_root).resolve() if configured_data_root else ROOT / "data") / ".semantic_cache"


# Kept for callers that inspect this module, while cache operations use the
# dynamic resolver above.
CACHE_ROOT = _cache_root()


def _key(model_id: str) -> str:
    return hashlib.sha1(model_id.encode("utf-8")).hexdigest()[:16]


def cache_path(model_id: str, asset_id: str) -> Path:
    path = _cache_root() / _key(model_id)
    path.mkdir(parents=True, exist_ok=True)
    return path / f"{asset_id}.npy"


def invalidate(model_id: str | None = None) -> int:
    """Remove semantic vector cache files, useful after model upgrades."""
    cache_root = _cache_root()
    root = cache_root / _key(model_id) if model_id else cache_root
    if not root.exists():
        return 0
    count = 0
    for path in root.rglob("*"):
        if path.is_file() and path.suffix in (".npy", ".json"):
            path.unlink()
            count += 1
    return count


def load(model_id: str, asset_id: str, file_path: str) -> Optional[list[float]]:
    path = cache_path(model_id, asset_id)
    meta = path.with_suffix(".json")
    try:
        stat = Path(file_path).stat()
        if not path.exists() or not meta.exists():
            return None
        data = json.loads(meta.read_text(encoding="utf-8"))
        if data.get("size") != stat.st_size or data.get("mtime_ns") != stat.st_mtime_ns:
            return None
        vector = np.load(path, allow_pickle=False).astype("float32")
        return vector.tolist()
    except Exception:
        return None


def save(model_id: str, asset_id: str, file_path: str, vector: list[float]) -> None:
    path = cache_path(model_id, asset_id)
    stat = Path(file_path).stat()
    np.save(path, np.asarray(vector, dtype="float32"), allow_pickle=False)
    path.with_suffix(".json").write_text(
        json.dumps({"size": stat.st_size, "mtime_ns": stat.st_mtime_ns}, ensure_ascii=False),
        encoding="utf-8",
    )
