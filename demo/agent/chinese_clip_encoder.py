"""Lazy Chinese-CLIP encoder with an official cn_clip first choice.

The package is optional.  If it is not installed, this module reports an
explicit unavailable state instead of blocking the technical analysis demo.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Optional

import numpy as np

from .model_paths import CHINESE_CLIP_DEFAULT_CHECKPOINT, CHINESE_CLIP_DIR

MODEL_ID = os.getenv("CHINESE_CLIP_MODEL_ID", "OFA-Sys/chinese-clip-vit-base-patch16")
_configured_checkpoint = os.getenv("CHINESE_CLIP_CHECKPOINT", "")
CHECKPOINT = _configured_checkpoint or (str(CHINESE_CLIP_DEFAULT_CHECKPOINT) if CHINESE_CLIP_DEFAULT_CHECKPOINT.is_file() else "")
CHECKPOINT_ROOT = Path(CHECKPOINT).parent if CHECKPOINT else CHINESE_CLIP_DIR
USE_MODELSCOPE = os.getenv("CHINESE_CLIP_USE_MODELSCOPE", "0").lower() in ("1", "true", "yes")
OFFLINE = os.getenv("PHOTO_SCREENER_OFFLINE", "0").lower() in ("1", "true", "yes")
DEVICE = os.getenv("SEMANTIC_DEVICE", "cpu")
_model = None
_preprocess = None
_tokenizer = None
_backend = None
_error: Optional[str] = None


def status() -> dict:
    return {
        "available": _model is not None,
        "model": MODEL_ID,
        "device": DEVICE,
        "backend": _backend,
        "checkpoint": CHECKPOINT,
        "error": _error,
    }


def _load() -> bool:
    global _model, _preprocess, _tokenizer, _backend, _error
    if _model is not None:
        return True
    if _error is not None:
        return False
    try:
        import torch
        from cn_clip.clip import load_from_name, tokenize

        if OFFLINE and not CHECKPOINT:
            raise FileNotFoundError(
                "Local Chinese-CLIP checkpoint was not found at "
                f"{CHINESE_CLIP_DEFAULT_CHECKPOINT}"
            )

        # The official loader resolves the local ``clip_cn_vit-b-16.pt`` by
        # model name and download root. Passing the checkpoint path as the
        # model name is not supported by cn_clip.
        _model, _preprocess = load_from_name(
            "ViT-B-16",
            device=DEVICE,
            download_root=str(CHECKPOINT_ROOT),
            use_modelscope=USE_MODELSCOPE,
        )
        _tokenizer = tokenize
        _model.eval()
        _backend = "cn_clip"
        return True
    except Exception as first_error:
        # Keep a useful error for the UI.  Transformers cannot generally load
        # the custom cn_clip checkpoints without the official package.
        _error = (
            f"cn_clip checkpoint unavailable: {first_error}. "
            "Install cn-clip and keep clip_cn_vit-b-16.pt under the configured model root."
        )
        return False


def encode_image(image) -> Optional[list[float]]:
    if not _load():
        return None
    try:
        import torch
        from PIL import Image

        if isinstance(image, np.ndarray):
            image = Image.fromarray(image)
        tensor = _preprocess(image).unsqueeze(0).to(DEVICE)
        with torch.no_grad():
            vector = _model.encode_image(tensor)
            vector = vector / vector.norm(dim=-1, keepdim=True)
        return vector[0].detach().cpu().numpy().astype("float32").tolist()
    except Exception as exc:
        global _error
        _error = str(exc)
        return None


def encode_text(text: str) -> Optional[list[float]]:
    if not _load():
        return None
    try:
        import torch

        tokens = _tokenizer([text]).to(DEVICE)
        with torch.no_grad():
            vector = _model.encode_text(tokens)
            vector = vector / vector.norm(dim=-1, keepdim=True)
        return vector[0].detach().cpu().numpy().astype("float32").tolist()
    except Exception as exc:
        global _error
        _error = str(exc)
        return None
