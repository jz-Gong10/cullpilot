"""Centralized local model directories used by the CullPilot algorithms."""

from __future__ import annotations

import os
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
_configured_root = os.getenv("PHOTO_SCREENER_MODEL_ROOT", "").strip()
MODEL_ROOT = Path(_configured_root or (PROJECT_ROOT / "models")).resolve()
HF_HOME = MODEL_ROOT / "huggingface"
DEEPFACE_HOME = MODEL_ROOT / "deepface_home"
CHINESE_CLIP_DIR = MODEL_ROOT / "chinese_clip"
CHINESE_CLIP_DEFAULT_CHECKPOINT = CHINESE_CLIP_DIR / "clip_cn_vit-b-16.pt"
DINO_MODEL_DIR = MODEL_ROOT / "dinov2-small"
