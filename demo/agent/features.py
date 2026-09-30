"""Image decoding and measurable feature extraction.

Face detection is intentionally delegated to :mod:`face_analysis` and is
performed once per image.  The resulting boxes are stored on ImageFeatures and
reused by every later stage.
"""

from __future__ import annotations

import hashlib
import os
from datetime import datetime
from typing import Optional

import cv2
import imagehash
import numpy as np
from PIL import ExifTags, Image

from .config import (
    HIGHLIGHT_THRESHOLD,
    LAPLACIAN_KSIZE,
    SHADOW_THRESHOLD,
    SUPPORTED_FORMATS,
)
from .schemas import FaceBox, ImageFeatures


def _env_enabled(name: str, default: str = "1") -> bool:
    return os.getenv(name, default).strip().lower() in ("1", "true", "yes", "on")


def compute_file_hash(file_path: str) -> str:
    sha256 = hashlib.sha256()
    with open(file_path, "rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            sha256.update(chunk)
    return sha256.hexdigest()[:16]


def decode_image(file_path: str) -> Optional[np.ndarray]:
    """Decode a path with numpy so Windows Chinese paths work."""
    try:
        raw = np.fromfile(file_path, dtype=np.uint8)
        return cv2.imdecode(raw, cv2.IMREAD_COLOR)
    except Exception:
        return None


def extract_exif_timestamp(img: Image.Image) -> Optional[float]:
    value, _ = extract_exif_datetime(img)
    if not value:
        return None
    try:
        return datetime.strptime(value, "%Y:%m:%d %H:%M:%S").timestamp()
    except Exception:
        return None


def extract_exif_datetime(img: Image.Image) -> tuple[Optional[str], Optional[float]]:
    try:
        exif = img.getexif()
        if not exif:
            return None, None
        for tag_id, value in exif.items():
            name = ExifTags.TAGS.get(tag_id, "")
            if name in ("DateTimeOriginal", "DateTime"):
                text = str(value)
                try:
                    return text, datetime.strptime(text, "%Y:%m:%d %H:%M:%S").timestamp()
                except ValueError:
                    return text, None
    except Exception:
        pass
    return None, None


def compute_sharpness(gray: np.ndarray) -> float:
    h, w = gray.shape[:2]
    if h < 2 or w < 2:
        return 0.0
    variance = float(cv2.Laplacian(gray, cv2.CV_64F, ksize=LAPLACIAN_KSIZE).var())
    # Normalize by pixel count to avoid rewarding high resolution alone.
    return float(max(0.0, min(1.0, variance / max(1.0, h * w) * 300.0)))


def compute_brightness(gray: np.ndarray) -> float:
    return float(np.mean(gray) / 255.0)


def compute_contrast(gray: np.ndarray) -> float:
    return float(max(0.0, min(1.0, float(np.std(gray)) / 128.0)))


def compute_highlight_clip(gray: np.ndarray) -> float:
    return float(np.mean(gray >= HIGHLIGHT_THRESHOLD))


def compute_shadow_clip(gray: np.ndarray) -> float:
    return float(np.mean(gray <= SHADOW_THRESHOLD))


def compute_color_histogram(img: np.ndarray) -> list:
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], None, [8, 8, 8], [0, 180, 0, 256, 0, 256])
    hist = cv2.normalize(hist, hist).flatten()
    return hist.astype(float).tolist()


def compute_texture_score(gray: np.ndarray) -> float:
    """A compact texture proxy used by scene clustering."""
    edges = cv2.Canny(gray, 80, 160)
    return float(np.mean(edges > 0))


def detect_has_face(img: np.ndarray) -> bool:
    from .face_analysis import detect_face_boxes
    return bool(detect_face_boxes(img))


def extract_features(img_path: str, face_boxes: Optional[list[FaceBox]] = None) -> Optional[ImageFeatures]:
    if not os.path.isfile(img_path) or os.path.splitext(img_path)[1].lower() not in SUPPORTED_FORMATS:
        return None

    img = decode_image(img_path)
    if img is None:
        print(f"  [WARN] 无法解码: {img_path}")
        return None

    try:
        with Image.open(img_path) as pil_img:
            exif_datetime, exif_timestamp = extract_exif_datetime(pil_img)
            try:
                phash = str(imagehash.phash(pil_img))
            except Exception:
                phash = ""
    except Exception as exc:
        print(f"  [WARN] Pillow 读取失败 {img_path}: {exc}")
        exif_datetime, exif_timestamp, phash = None, None, ""

    if face_boxes is None:
        from .face_analysis import detect_face_boxes
        face_boxes = detect_face_boxes(img)

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    boxes = list(face_boxes or [])
    timestamp = exif_timestamp if exif_timestamp is not None else os.path.getmtime(img_path)
    return ImageFeatures(
        asset_id=compute_file_hash(img_path),
        file_path=os.path.abspath(img_path),
        file_name=os.path.basename(img_path),
        has_face=bool(boxes),
        sharpness=compute_sharpness(gray),
        brightness=compute_brightness(gray),
        contrast=compute_contrast(gray),
        highlight_clip=compute_highlight_clip(gray),
        shadow_clip=compute_shadow_clip(gray),
        color_histogram=compute_color_histogram(img),
        phash=phash,
        timestamp=timestamp,
        width=int(img.shape[1]),
        height=int(img.shape[0]),
        file_size=os.path.getsize(img_path),
        exif_datetime=exif_datetime,
        face_count=len(boxes),
        face_boxes=boxes,
        texture_score=compute_texture_score(gray),
    )


def batch_extract(directory: str) -> list[ImageFeatures]:
    print(f"[特征提取] 扫描目录: {directory}")
    results = []
    if not os.path.isdir(directory):
        return results

    for root, _, files in os.walk(directory):
        for fname in sorted(files):
            if os.path.splitext(fname)[1].lower() not in SUPPORTED_FORMATS:
                continue
            path = os.path.join(root, fname)
            try:
                from .face_analysis import detect_face_boxes
                boxes = detect_face_boxes(decode_image(path))
                feat = extract_features(path, boxes)
            except Exception as exc:
                print(f"  [WARN] {fname} 分析失败: {exc}")
                feat = None
            if feat is not None:
                results.append(feat)
                print(f"  ✓ {fname} sharpness={feat.sharpness:.2f} faces={feat.face_count}")
            else:
                print(f"  ✗ {fname} failed")
    print(f"[特征提取] 完成: {len(results)} 张图片\n")
    return results


def enrich_visual_embeddings(features_list: list[ImageFeatures]) -> dict:
    """Best-effort DINOv2 enrichment; technical extraction never depends on it."""
    from . import semantic_cache
    from . import semantic_encoder

    if not _env_enabled("ENABLE_DINO"):
        for feature in features_list:
            feature.visual_embedding = None
            feature.semantic_status = "disabled"
        return {
            "available": False,
            "enabled": False,
            "disabled": True,
            "model": semantic_encoder.MODEL_ID,
            "device": semantic_encoder.DEVICE,
            "error": None,
        }

    result = semantic_encoder.status()
    for feature in features_list:
        vector = semantic_cache.load(semantic_encoder.MODEL_ID, feature.asset_id, feature.file_path)
        if vector is None:
            try:
                with Image.open(feature.file_path) as image:
                    vector = semantic_encoder.encode_image(image.convert("RGB"))
            except Exception:
                vector = None
            if vector is not None:
                semantic_cache.save(semantic_encoder.MODEL_ID, feature.asset_id, feature.file_path, vector)
        feature.visual_embedding = vector
        feature.semantic_status = "ok" if vector is not None else "unavailable"
    result = semantic_encoder.status()
    result["enabled"] = any(feature.visual_embedding for feature in features_list)
    return result


def enrich_clip_embeddings(features_list: list[ImageFeatures]) -> dict:
    """Best-effort Chinese-CLIP image embeddings for semantic search."""
    from . import chinese_clip_encoder, semantic_cache

    if not _env_enabled("ENABLE_CHINESE_CLIP"):
        for feature in features_list:
            feature.clip_image_embedding = None
        return {
            "available": False,
            "enabled": False,
            "disabled": True,
            "model": chinese_clip_encoder.MODEL_ID,
            "device": chinese_clip_encoder.DEVICE,
            "backend": None,
            "error": None,
        }

    for feature in features_list:
        vector = semantic_cache.load(chinese_clip_encoder.MODEL_ID, feature.asset_id, feature.file_path)
        if vector is None:
            try:
                with Image.open(feature.file_path) as image:
                    vector = chinese_clip_encoder.encode_image(image.convert("RGB"))
            except Exception:
                vector = None
            if vector is not None:
                semantic_cache.save(chinese_clip_encoder.MODEL_ID, feature.asset_id, feature.file_path, vector)
        feature.clip_image_embedding = vector
    result = chinese_clip_encoder.status()
    result["enabled"] = any(feature.clip_image_embedding for feature in features_list)
    return result
