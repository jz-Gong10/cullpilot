"""Lazy DINOv2 image encoder for visual image-to-image similarity."""

from __future__ import annotations

import os
from typing import Optional

import numpy as np

from .model_paths import DINO_MODEL_DIR, HF_HOME

MODEL_ID = os.getenv("DINO_MODEL_ID", "facebook/dinov2-small")
DEVICE = os.getenv("SEMANTIC_DEVICE", "cpu")
os.environ.setdefault("HF_HOME", str(HF_HOME))

_processor = None
_model = None
_error: Optional[str] = None


def status() -> dict:
    return {"available": _model is not None, "model": MODEL_ID, "device": DEVICE, "error": _error}


def _load() -> bool:
    global _processor, _model, _error
    if _model is not None:
        return True
    if _error is not None:
        return False
    try:
        import torch
        from transformers import AutoImageProcessor, AutoModel

        use_local_dino = MODEL_ID == "facebook/dinov2-small" and (DINO_MODEL_DIR / "config.json").is_file()
        model_source = str(DINO_MODEL_DIR) if use_local_dino else MODEL_ID
        local_only = os.getenv("PHOTO_SCREENER_OFFLINE", "0").lower() in ("1", "true", "yes")
        _processor = AutoImageProcessor.from_pretrained(model_source, local_files_only=local_only)
        _model = AutoModel.from_pretrained(model_source, local_files_only=local_only)
        _model.eval().to(DEVICE)
    except Exception as exc:
        _error = str(exc)
        return False
    return True


def encode_image(image) -> Optional[list[float]]:
    """Encode a PIL image or RGB numpy array; return a unit vector."""
    if not _load():
        return None
    global _error
    try:
        import torch
        from PIL import Image

        if isinstance(image, np.ndarray):
            image = Image.fromarray(image)
        inputs = _processor(images=image, return_tensors="pt")
        inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
        with torch.no_grad():
            outputs = _model(**inputs)
            vector = outputs.last_hidden_state[:, 0, :]
            vector = torch.nn.functional.normalize(vector, p=2, dim=-1)
        return vector[0].detach().cpu().numpy().astype("float32").tolist()
    except Exception as exc:
        _error = str(exc)
        return None
