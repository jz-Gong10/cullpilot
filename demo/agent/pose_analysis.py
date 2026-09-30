"""Lightweight, explainable pose labels for single-person photographs.

The detector is deliberately conservative: if the body is cropped or keypoint
visibility is poor, the image is assigned ``no_specific_pose`` instead of a
guess. Multi-person photos are classified by face count by the service layer.
"""

from __future__ import annotations

import math
from typing import Optional

import cv2
import numpy as np

try:
    import mediapipe as mp
except ImportError:  # Pose is optional; the rest of the pipeline remains usable.
    mp = None

_mp_solutions = getattr(mp, "solutions", None) if mp is not None else None
if mp is not None and not all(
    hasattr(_mp_solutions, name)
    for name in ("pose", "hands")
):
    # Keep the runtime behavior explicit when a newer MediaPipe package is
    # installed without the legacy Solutions API.
    mp = None

from .features import decode_image
from .schemas import FaceAnalysis, PoseInfo


BODY_NO_POSE = "no_specific_pose"
BODY_STANDING = "standing"
BODY_SITTING = "sitting"
BODY_SQUAT = "squat_or_kneel"
HAND_NONE = "no_specific_hand_action"
HAND_RAISED = "raised_hand"
HAND_PEACE = "peace_sign"
HAND_FACE = "hand_near_face"

BODY_POSE_NAMES = {
    BODY_STANDING: "站立",
    BODY_SITTING: "坐姿",
    BODY_SQUAT: "蹲下/跪坐",
    BODY_NO_POSE: "无特定姿态",
}
HAND_ACTION_NAMES = {
    HAND_NONE: "无特定手势",
    HAND_RAISED: "抬手/挥手样式",
    HAND_PEACE: "比耶",
    HAND_FACE: "托脸/手扶脸",
}


def body_pose_name(value: str | None) -> str:
    return BODY_POSE_NAMES.get(value or BODY_NO_POSE, "无特定姿态")


def hand_action_name(value: str | None) -> str:
    return HAND_ACTION_NAMES.get(value or HAND_NONE, "无特定手势")

_POSE_LANDMARKS = {
    "nose": 0, "left_shoulder": 11, "right_shoulder": 12,
    "left_elbow": 13, "right_elbow": 14, "left_wrist": 15, "right_wrist": 16,
    "left_hip": 23, "right_hip": 24, "left_knee": 25, "right_knee": 26,
    "left_ankle": 27, "right_ankle": 28,
}


def _angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    first, second = a - b, c - b
    denominator = np.linalg.norm(first) * np.linalg.norm(second)
    if denominator < 1e-8:
        return 180.0
    value = float(np.dot(first, second) / denominator)
    return math.degrees(math.acos(max(-1.0, min(1.0, value))))


def _point(landmarks, index: int) -> tuple[float, float, float]:
    item = landmarks[index]
    return float(item.x), float(item.y), float(getattr(item, "visibility", 1.0))


def _classify_body(points: dict[str, tuple[float, float, float]]) -> tuple[str, float, float]:
    required = ["left_shoulder", "right_shoulder", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle"]
    visible = sum(points[name][2] >= 0.45 for name in required) / len(required)
    if visible < 0.55:
        return BODY_NO_POSE, visible * 0.8, visible

    def vec(name: str) -> np.ndarray:
        return np.asarray(points[name][:2], dtype=float)

    left_knee = _angle(vec("left_hip"), vec("left_knee"), vec("left_ankle"))
    right_knee = _angle(vec("right_hip"), vec("right_knee"), vec("right_ankle"))
    knee_angle = (left_knee + right_knee) / 2.0
    hip_y = (points["left_hip"][1] + points["right_hip"][1]) / 2.0
    knee_y = (points["left_knee"][1] + points["right_knee"][1]) / 2.0
    shoulder_y = (points["left_shoulder"][1] + points["right_shoulder"][1]) / 2.0

    # A seated person normally has bent knees with the hips at or above the
    # knees in image coordinates. A squat/kneel has a substantially compressed
    # torso or sharply bent knees.
    # Keep this threshold strict because a seated person with an occluded
    # lower body is otherwise easily mistaken for a squat.
    if knee_angle < 105.0 and hip_y > shoulder_y + 0.18:
        return BODY_SQUAT, min(1.0, visible * 0.75 + 0.25), visible
    if knee_angle < 150.0 and hip_y <= knee_y + 0.14:
        return BODY_SITTING, min(1.0, visible * 0.8 + 0.2), visible
    if knee_angle >= 150.0:
        return BODY_STANDING, min(1.0, visible * 0.8 + 0.2), visible
    return BODY_NO_POSE, visible * 0.75, visible


def _finger_extended(hand, tip: int, pip: int, wrist: int = 0) -> bool:
    tip_point = np.asarray([hand[tip].x, hand[tip].y])
    pip_point = np.asarray([hand[pip].x, hand[pip].y])
    wrist_point = np.asarray([hand[wrist].x, hand[wrist].y])
    return float(np.linalg.norm(tip_point - wrist_point)) > float(np.linalg.norm(pip_point - wrist_point)) * 1.08


def _classify_hands(hand_result, points: dict[str, tuple[float, float, float]], face: FaceAnalysis) -> tuple[str, float, list]:
    hands = hand_result.multi_hand_landmarks or []
    serialized = []
    if not hands:
        return HAND_NONE, 0.45, serialized

    raised = False
    near_face = False
    for hand_landmarks in hands[:2]:
        hand = hand_landmarks.landmark
        serialized.append([[float(item.x), float(item.y), float(item.z)] for item in hand])
        index_open = _finger_extended(hand, 8, 6)
        middle_open = _finger_extended(hand, 12, 10)
        ring_open = _finger_extended(hand, 16, 14)
        pinky_open = _finger_extended(hand, 20, 18)
        if index_open and middle_open and not ring_open and not pinky_open:
            return HAND_PEACE, 0.9, serialized

        wrist_y = float(hand[0].y)
        shoulder_y = min(points["left_shoulder"][1], points["right_shoulder"][1])
        raised = raised or wrist_y < shoulder_y - 0.04
        if face.faces:
            fx, fy, fw, fh = face.faces[0].bbox
            # Pose and face coordinates are normalized separately here; the
            # proximity test uses the face center as a stable coarse anchor.
            face_center = face.faces[0].center
            palm = np.asarray([float(hand[9].x), float(hand[9].y)])
            near_face = near_face or float(np.linalg.norm(palm - np.asarray(face_center))) < 0.18

    if near_face:
        return HAND_FACE, 0.72, serialized
    if raised:
        return HAND_RAISED, 0.78, serialized
    return HAND_NONE, 0.55, serialized


def analyze_pose(img_path: str, face_analysis: Optional[FaceAnalysis] = None) -> PoseInfo:
    face = face_analysis or FaceAnalysis(asset_id=img_path)
    person_count = int(face.face_count)
    if person_count != 1:
        return PoseInfo(asset_id=img_path, person_count=person_count, analysis_status="skipped_multi_person")

    if mp is None:
        return PoseInfo(asset_id=img_path, person_count=person_count, analysis_status="unavailable")

    image = decode_image(img_path)
    if image is None:
        return PoseInfo(asset_id=img_path, person_count=person_count, analysis_status="decode_error", analysis_error="无法解码图片")

    height, width = image.shape[:2]
    scale = min(1.0, 1280.0 / max(height, width))
    work = cv2.resize(image, (max(1, int(width * scale)), max(1, int(height * scale)))) if scale < 1.0 else image
    rgb = cv2.cvtColor(work, cv2.COLOR_BGR2RGB)
    try:
        with mp.solutions.pose.Pose(static_image_mode=True, model_complexity=1, min_detection_confidence=0.45) as pose:
            pose_result = pose.process(rgb)
        if not pose_result.pose_landmarks:
            return PoseInfo(asset_id=img_path, person_count=person_count, analysis_status="no_landmarks", analysis_error="未检测到人体关键点")

        points = {name: _point(pose_result.pose_landmarks.landmark, index) for name, index in _POSE_LANDMARKS.items()}
        body_pose, body_confidence, visible_ratio = _classify_body(points)
        with mp.solutions.hands.Hands(static_image_mode=True, max_num_hands=2, min_detection_confidence=0.4) as hands:
            hand_result = hands.process(rgb)
        hand_action, hand_confidence, hand_keypoints = _classify_hands(hand_result, points, face)
        keypoints = {name: list(value) for name, value in points.items()}
        confidence = round(max(0.0, min(1.0, 0.7 * body_confidence + 0.3 * hand_confidence)), 3)
        return PoseInfo(
            asset_id=img_path,
            body_pose=body_pose,
            hand_action=hand_action,
            pose_confidence=confidence,
            visible_ratio=round(visible_ratio, 3),
            person_count=person_count,
            keypoints=keypoints,
            hand_keypoints=hand_keypoints,
        )
    except Exception as exc:
        return PoseInfo(asset_id=img_path, person_count=person_count, analysis_status="analysis_error", analysis_error=str(exc))


def batch_analyze_poses(asset_paths: dict[str, str], faces: dict[str, FaceAnalysis]) -> dict[str, PoseInfo]:
    results: dict[str, PoseInfo] = {}
    for asset_id, path in asset_paths.items():
        try:
            results[asset_id] = analyze_pose(path, faces.get(asset_id))
        except Exception as exc:
            count = faces.get(asset_id).face_count if faces.get(asset_id) else 0
            results[asset_id] = PoseInfo(asset_id=asset_id, person_count=count, analysis_status="analysis_error", analysis_error=str(exc))
    return results
