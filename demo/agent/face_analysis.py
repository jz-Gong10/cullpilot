"""Single-pass multi-face detection and per-face analysis."""

from __future__ import annotations

import gc
import os
import sys
from pathlib import Path
from typing import Optional

os.environ.setdefault("DEEPFACE_BACKEND_ENGINE", "pytorch")

import cv2
import numpy as np

try:
    import mediapipe as mp
except Exception as exc:  # Basic feature extraction can run without AI extras.
    mp = None
    _MEDIAPIPE_ERROR = str(exc)
else:
    _MEDIAPIPE_ERROR = None

_mp_solutions = getattr(mp, "solutions", None) if mp is not None else None
if mp is not None and not all(
    hasattr(_mp_solutions, name)
    for name in ("face_detection", "face_mesh")
):
    # MediaPipe 0.10.35 imports successfully but removed the legacy Solutions
    # modules used by this algorithm package. Treat it as unavailable instead
    # of returning empty detections without an actionable warning.
    _MEDIAPIPE_ERROR = (
        "Installed MediaPipe does not provide the legacy mp.solutions API; "
        "install mediapipe==0.10.21"
    )
    mp = None

from .model_paths import DEEPFACE_HOME

# DeepFace stores its downloaded files below $DEEPFACE_HOME/.deepface/weights.
# Set this before importing DeepFace so the server never depends on a user's
# home directory.
os.environ.setdefault("DEEPFACE_HOME", str(DEEPFACE_HOME))

from .config import (
    CLUSTER_MIN_FACE_CONFIDENCE,
    EAR_CLOSED_THRESHOLD,
    FACE_COMPLETENESS_MARGIN,
    FACE_MIN_SIZE_RATIO,
    FACE_NMS_IOU_THRESHOLD,
    FACE_DETECTION_MAX_SIDE,
    FACE_DETECTION_TILE_OVERLAP,
    FRONTAL_YAW_THRESHOLD,
)
from .schemas import FaceAnalysis, FaceBox, FaceInfo

DeepFace = None
_DEEPFACE_AVAILABLE = False
_DEEPFACE_IMPORT_ERROR = None


def _configure_console_encoding() -> None:
    """Prevent DeepFace's warning logger from failing on Windows GBK consoles."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (OSError, ValueError):
            pass


if os.getenv("ANALYSIS_ENABLE_DEEPFACE", "true").lower() in ("1", "true", "yes", "on"):
    try:
        _configure_console_encoding()
        from deepface import DeepFace
        _DEEPFACE_AVAILABLE = True
    except Exception as exc:  # DeepFace is optional for feature-only testing.
        _DEEPFACE_IMPORT_ERROR = str(exc)


LEFT_EAR_IDX = [33, 160, 158, 133, 153, 144]
RIGHT_EAR_IDX = [362, 385, 387, 263, 373, 380]


def _iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    x1, y1 = max(ax, bx), max(ay, by)
    x2, y2 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


def nms_face_boxes(boxes: list[FaceBox], iou_threshold: float = FACE_NMS_IOU_THRESHOLD) -> list[FaceBox]:
    ordered = sorted(boxes, key=lambda item: item.confidence, reverse=True)
    kept: list[FaceBox] = []
    for candidate in ordered:
        if all(_iou(candidate.bbox, current.bbox) < iou_threshold for current in kept):
            kept.append(candidate)
    return kept


def detect_face_boxes(img: Optional[np.ndarray]) -> list[FaceBox]:
    """Detect faces at full, enlarged and tiled scales, then merge duplicates.

    MediaPipe's short-range detector is reliable for normal portraits but its
    receptive field makes small background faces easy to miss.  Running the
    same detector on an enlarged image and overlapping tiles improves recall
    without changing the downstream DeepFace contract.
    """
    if mp is None or img is None or img.size == 0:
        return []

    height, width = img.shape[:2]
    candidates: list[FaceBox] = []

    def run_pass(pass_img: np.ndarray, offset_x: int = 0, offset_y: int = 0, scale_back: float = 1.0, thorough: bool = True):
        ph, pw = pass_img.shape[:2]
        rgb = cv2.cvtColor(pass_img, cv2.COLOR_BGR2RGB)
        detector_cls = mp.solutions.face_detection.FaceDetection
        model_options = (0, 1) if thorough else (1,)
        confidence_options = (0.5, 0.3) if thorough else (0.25,)
        for model_selection in model_options:
            for confidence in confidence_options:
                with detector_cls(model_selection=model_selection, min_detection_confidence=confidence) as detector:
                    result = detector.process(rgb)
                for detection in result.detections or []:
                    rel = detection.location_data.relative_bounding_box
                    x = int(rel.xmin * pw)
                    y = int(rel.ymin * ph)
                    w = int(rel.width * pw)
                    h = int(rel.height * ph)
                    if w <= 0 or h <= 0:
                        continue
                    # Map tile/enlarged coordinates back to original image.
                    x = int(x / scale_back + offset_x)
                    y = int(y / scale_back + offset_y)
                    w = int(w / scale_back)
                    h = int(h / scale_back)
                    x = max(0, min(width - 1, x))
                    y = max(0, min(height - 1, y))
                    w = min(width - x, w)
                    h = min(height - y, h)
                    if w <= 0 or h <= 0:
                        continue
                    score = float(detection.score[0]) if detection.score else confidence
                    candidates.append(FaceBox((x, y, w, h), score))

    try:
        # 1) Original image preserves large/normal faces.
        run_pass(img)

        # 2) Enlarged pass improves small faces. Cap the working size to keep
        # runtime bounded for phone images with very large dimensions.
        max_side = max(height, width)
        target_side = min(FACE_DETECTION_MAX_SIDE, max_side * 2)
        if max_side > 0 and target_side > max_side * 1.05:
            scale = target_side / max_side
            enlarged = cv2.resize(img, (int(width * scale), int(height * scale)), interpolation=cv2.INTER_CUBIC)
            run_pass(enlarged, scale_back=scale)

        # 3) Overlapping tiles provide a larger face relative to the detector
        # frame for group shots. Keep the tile count bounded.
        tile_w, tile_h = min(width, max(640, width // 2)), min(height, max(640, height // 2))
        if width > tile_w * 1.05 or height > tile_h * 1.05:
            step_x = max(1, int(tile_w * (1.0 - FACE_DETECTION_TILE_OVERLAP)))
            step_y = max(1, int(tile_h * (1.0 - FACE_DETECTION_TILE_OVERLAP)))
            for y0 in range(0, max(1, height - tile_h + 1), step_y):
                for x0 in range(0, max(1, width - tile_w + 1), step_x):
                    tile = img[y0:min(height, y0 + tile_h), x0:min(width, x0 + tile_w)]
                    run_pass(tile, offset_x=x0, offset_y=y0, thorough=False)
            # Ensure the bottom/right edges are covered when the range step
            # does not land exactly on the image boundary.
            if height > tile_h or width > tile_w:
                run_pass(img[max(0, height - tile_h):height, max(0, width - tile_w):width], max(0, width - tile_w), max(0, height - tile_h), thorough=False)
    except Exception:
        return []

    valid = []
    for candidate in candidates:
        x, y, w, h = candidate.bbox
        if (w * h) / float(width * height) >= FACE_MIN_SIZE_RATIO and candidate.confidence >= CLUSTER_MIN_FACE_CONFIDENCE:
            valid.append(candidate)
    return nms_face_boxes(valid)


def _crop_face(img: np.ndarray, bbox: tuple[int, int, int, int], margin_ratio: float = 0.30) -> np.ndarray:
    x, y, w, h = bbox
    ih, iw = img.shape[:2]
    mx, my = int(w * margin_ratio), int(h * margin_ratio)
    x1, y1 = max(0, x - mx), max(0, y - my)
    x2, y2 = min(iw, x + w + mx), min(ih, y + h + my)
    return img[y1:y2, x1:x2]


def _calc_ear(points: list[tuple[int, int]], indexes: list[int]) -> float:
    p = [np.asarray(points[index], dtype=float) for index in indexes]
    return float((np.linalg.norm(p[1] - p[5]) + np.linalg.norm(p[2] - p[4])) / (2 * np.linalg.norm(p[0] - p[3]) + 1e-6))


def _run_mesh(face_rgb: np.ndarray) -> tuple[Optional[float], Optional[bool], Optional[float], Optional[float], Optional[bool]]:
    """MediaPipe expects RGB here; do not convert the image a second time."""
    if mp is None:
        return None, None, None, None, None
    h, w = face_rgb.shape[:2]
    try:
        with mp.solutions.face_mesh.FaceMesh(static_image_mode=True, max_num_faces=1, min_detection_confidence=0.5) as mesh:
            result = mesh.process(face_rgb)
        if not result.multi_face_landmarks:
            return None, None, None, None, None
        points = [(int(lm.x * w), int(lm.y * h)) for lm in result.multi_face_landmarks[0].landmark]
        ear = (_calc_ear(points, LEFT_EAR_IDX) + _calc_ear(points, RIGHT_EAR_IDX)) / 2.0
        left_eye, right_eye, nose = np.asarray(points[33]), np.asarray(points[263]), np.asarray(points[1])
        eye_width = abs(float(right_eye[0] - left_eye[0])) + 1e-6
        yaw = float((nose[0] - (left_eye[0] + right_eye[0]) / 2.0) / eye_width * 45.0)
        eye_y = (left_eye[1] + right_eye[1]) / 2.0
        face_height = max(1.0, float(max(y for _, y in points) - min(y for _, y in points)))
        pitch = max(-30.0, min(30.0, float((nose[1] - eye_y) / face_height * 30.0)))
        mouth_left, mouth_right = np.asarray(points[61]), np.asarray(points[291])
        smile_proxy = float(np.linalg.norm(mouth_right - mouth_left) / max(1.0, face_height))
        return ear, ear >= EAR_CLOSED_THRESHOLD, yaw, pitch, smile_proxy > 0.28
    except Exception:
        return None, None, None, None, None


def _run_deepface(face_bgr: np.ndarray) -> tuple[Optional[float], Optional[str], Optional[str], Optional[list], Optional[str]]:
    if not _DEEPFACE_AVAILABLE or DeepFace is None:
        return None, None, None, None, "DeepFace unavailable"
    age = gender = emotion = embedding = None
    errors = []
    try:
        result = DeepFace.represent(
            img_path=face_bgr,
            detector_backend="skip",
            enforce_detection=False,
            model_name="VGG-Face",
        )
        item = result[0] if isinstance(result, list) and result else result
        if isinstance(item, dict) and item.get("embedding") is not None:
            embedding = [float(value) for value in item["embedding"]]
    except Exception as exc:
        errors.append(f"represent: {exc}")
    try:
        result = DeepFace.analyze(
            img_path=face_bgr,
            actions=["emotion", "age", "gender"],
            detector_backend="skip",
            enforce_detection=False,
            silent=True,
        )
        item = result[0] if isinstance(result, list) and result else result
        if isinstance(item, dict):
            age = float(item["age"]) if item.get("age") is not None else None
            gender = str(item.get("dominant_gender")) if item.get("dominant_gender") is not None else None
            emotion = str(item.get("dominant_emotion")) if item.get("dominant_emotion") is not None else None
    except Exception as exc:
        errors.append(f"analyze: {exc}")
    gc.collect()
    return age, gender, emotion, embedding, "; ".join(errors) if errors else None


def analyze_faces(img_path: str, face_boxes: Optional[list[FaceBox]] = None) -> FaceAnalysis:
    from .features import decode_image

    img = decode_image(img_path)
    if img is None:
        return FaceAnalysis(asset_id=img_path, detector_status="decode_error", detector_error="无法解码图片")
    boxes = face_boxes if face_boxes is not None else detect_face_boxes(img)
    height, width = img.shape[:2]
    result = FaceAnalysis(asset_id=img_path, face_count=len(boxes))

    for index, face_box in enumerate(boxes, start=1):
        x, y, bw, bh = face_box.bbox
        crop_bgr = _crop_face(img, face_box.bbox)
        crop_rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        ear, eyes_open, yaw, pitch, smiling = _run_mesh(crop_rgb)
        age, gender, emotion, embedding, deepface_error = _run_deepface(crop_bgr)
        margin_x, margin_y = FACE_COMPLETENESS_MARGIN * width, FACE_COMPLETENESS_MARGIN * height
        completeness = 1.0
        if x <= margin_x or x + bw >= width - margin_x:
            completeness -= 0.25
        if y <= margin_y or y + bh >= height - margin_y:
            completeness -= 0.25
        face = FaceInfo(
            face_id=index,
            bbox=(x, y, bw, bh),
            center=((x + bw / 2) / width, (y + bh / 2) / height),
            face_size_ratio=(bw * bh) / float(width * height),
            detection_confidence=face_box.confidence,
            age=age,
            gender=gender,
            emotion=emotion,
            eye_aspect_ratio=ear,
            eyes_open=eyes_open,
            head_yaw=yaw,
            head_pitch=pitch,
            embedding=embedding,
            face_completeness=max(0.0, completeness),
            is_frontal=abs(yaw) < FRONTAL_YAW_THRESHOLD if yaw is not None else False,
            is_smiling=bool(smiling) if smiling is not None else (emotion == "happy"),
            analysis_status="ok" if deepface_error is None else "partial",
            analysis_error=deepface_error,
        )
        result.faces.append(face)
    return result


def batch_analyze(face_asset_ids: list[str], asset_paths: dict[str, str], face_boxes_map: Optional[dict[str, list[FaceBox]]] = None) -> dict[str, FaceAnalysis]:
    results: dict[str, FaceAnalysis] = {}
    for asset_id in face_asset_ids:
        path = asset_paths.get(asset_id)
        if not path:
            continue
        try:
            results[asset_id] = analyze_faces(path, (face_boxes_map or {}).get(asset_id))
        except Exception as exc:
            results[asset_id] = FaceAnalysis(asset_id=asset_id, detector_status="analysis_error", detector_error=str(exc))
    return results
