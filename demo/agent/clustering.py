"""Two-route, average-linkage image clustering.

The previous DSU implementation allowed bridge chains (A~B and B~C merged
even when A and C were dissimilar).  This module uses average-linkage merging,
which keeps a cluster internally coherent for the small demo datasets.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Optional

import numpy as np
from scipy.optimize import linear_sum_assignment

from .config import (
    CLUSTER_THRESHOLD,
    NO_FACE_SIMILARITY_WEIGHTS,
    FACE_MATCH_MIN_SIMILARITY,
    FACE_CONTEXT_MIN_SIMILARITY,
)
from .schemas import FaceAnalysis, ImageFeatures, PhotoGroup, PoseInfo


def _phash_distance(a: str, b: str) -> float:
    if not a or not b:
        return 1.0
    try:
        return min(1.0, bin(int(a, 16) ^ int(b, 16)).count("1") / 64.0)
    except (TypeError, ValueError):
        return 1.0


def _color_distance(a: list, b: list) -> float:
    va, vb = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    if va.size == 0 or vb.size == 0 or va.shape != vb.shape:
        return 1.0
    denominator = va + vb + 1e-9
    return float(min(1.0, np.sum((va - vb) ** 2 / denominator) / 10.0))


def _time_distance(a: ImageFeatures, b: ImageFeatures, window: float = 600.0) -> float:
    if a.timestamp is None or b.timestamp is None:
        return 0.5
    return float(min(1.0, abs(a.timestamp - b.timestamp) / window))


def _texture_distance(a: ImageFeatures, b: ImageFeatures) -> float:
    return float(min(1.0, abs(a.texture_score - b.texture_score) * 8.0 + abs(a.contrast - b.contrast) * 0.5))


def _cosine_similarity(a: list | None, b: list | None) -> Optional[float]:
    if not a or not b:
        return None
    va, vb = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    na, nb = np.linalg.norm(va), np.linalg.norm(vb)
    if na < 1e-12 or nb < 1e-12:
        return None
    return float(max(-1.0, min(1.0, np.dot(va, vb) / (na * nb))))


def _face_similarity(a: FaceAnalysis | None, b: FaceAnalysis | None) -> float:
    """Match individual faces instead of averaging a group embedding."""
    if not a or not b or not a.faces or not b.faces:
        return 0.5
    matrix = np.full((len(a.faces), len(b.faces)), 0.5, dtype=float)
    for i, face_a in enumerate(a.faces):
        for j, face_b in enumerate(b.faces):
            similarity = _cosine_similarity(face_a.embedding, face_b.embedding)
            if similarity is not None:
                matrix[i, j] = (similarity + 1.0) / 2.0
    rows, cols = linear_sum_assignment(1.0 - matrix)
    matched = [matrix[row, col] for row, col in zip(rows, cols)]
    embedding_score = float(np.mean(matched)) if matched else 0.5
    count_score = 1.0 - abs(len(a.faces) - len(b.faces)) / max(len(a.faces), len(b.faces), 1)
    return 0.85 * embedding_score + 0.15 * count_score


def _embedding_similarity(a: list | None, b: list | None) -> Optional[float]:
    if not a or not b:
        return None
    va, vb = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    na, nb = np.linalg.norm(va), np.linalg.norm(vb)
    if na < 1e-12 or nb < 1e-12:
        return None
    return float(max(0.0, min(1.0, (np.dot(va, vb) / (na * nb) + 1.0) / 2.0)))


def compute_similarity_breakdown(a: ImageFeatures, b: ImageFeatures, has_face: bool, face_map: dict[str, FaceAnalysis] | None = None) -> dict:
    phash = 1.0 - _phash_distance(a.phash, b.phash)
    color = 1.0 - _color_distance(a.color_histogram, b.color_histogram)
    time = 1.0 - _time_distance(a, b)
    dino_semantic = _embedding_similarity(a.visual_embedding, b.visual_embedding)
    clip_semantic = _embedding_similarity(a.clip_image_embedding, b.clip_image_embedding)
    semantic_values = [value for value in (dino_semantic, clip_semantic) if value is not None]
    semantic = float(np.mean(semantic_values)) if semantic_values else None
    semantic_models = []
    if dino_semantic is not None:
        semantic_models.append("facebook/dinov2-small")
    if clip_semantic is not None:
        semantic_models.append("OFA-Sys/chinese-clip-vit-base-patch16")
    if not has_face:
        texture = 1.0 - _texture_distance(a, b)
        values = {"phash": phash, "color": color, "texture": texture, "time": time}
        weights = {"phash": 0.25, "color": 0.20, "texture": 0.15, "time": 0.10}
        if semantic is not None:
            values["semantic"], weights["semantic"] = semantic, 0.30
        if clip_semantic is not None:
            values["clip_semantic"] = clip_semantic
        total = sum(weights.values())
        return {
            "similarity": sum(weights[key] * values[key] for key in weights) / total,
            **values,
            "semantic_available": semantic is not None,
            "semantic_models": semantic_models,
        }

    face_score = _face_similarity((face_map or {}).get(a.asset_id), (face_map or {}).get(b.asset_id))
    count_score = 1.0 - abs(a.face_count - b.face_count) / max(a.face_count, b.face_count, 1)
    # Background/context is deliberately separated from identity. It is the
    # same visual evidence used by the scene route, plus DINOv2 semantics.
    context_values = {"phash": phash, "color": color, "time": time}
    context_weights = {"phash": 0.30, "color": 0.30, "time": 0.10}
    if semantic is not None:
        context_values["semantic"], context_weights["semantic"] = semantic, 0.30
    if clip_semantic is not None:
        context_values["clip_semantic"] = clip_semantic
    context_score = sum(context_weights[key] * context_values[key] for key in context_weights) / sum(context_weights.values())
    # Identity and context both matter. A high face score alone is not enough
    # to merge two photos taken in unrelated places.
    values = {"face": face_score, "face_count": count_score, "context": context_score, **context_values}
    weights = {"face": 0.45, "face_count": 0.10, "context": 0.45}
    total = sum(weights.values())
    return {
        "similarity": sum(weights[key] * values[key] for key in weights) / total,
        **values,
        "semantic_available": semantic is not None,
        "semantic_models": semantic_models,
    }


def compute_similarity(a: ImageFeatures, b: ImageFeatures, has_face: bool, face_map: dict[str, FaceAnalysis] | None = None) -> float:
    return float(max(0.0, min(1.0, compute_similarity_breakdown(a, b, has_face, face_map)["similarity"])))


def _average_distance(left: list[int], right: list[int], matrix: np.ndarray) -> float:
    return float(np.mean([matrix[i, j] for i in left for j in right]))


def _average_linkage(indices: list[int], matrix: np.ndarray, threshold: float, allowed: np.ndarray | None = None) -> list[list[int]]:
    clusters = [[index] for index in indices]
    while len(clusters) > 1:
        best = None
        best_distance = float("inf")
        for i in range(len(clusters)):
            for j in range(i + 1, len(clusters)):
                if allowed is not None and not all(allowed[left, right] for left in clusters[i] for right in clusters[j]):
                    continue
                distance = _average_distance(clusters[i], clusters[j], matrix)
                if distance < best_distance:
                    best_distance, best = distance, (i, j)
        if best is None or best_distance > threshold:
            break
        i, j = best
        clusters[i].extend(clusters[j])
        clusters.pop(j)
    return clusters


def _build_distance_matrix(features: list[ImageFeatures], face_map: dict[str, FaceAnalysis], has_face: bool) -> np.ndarray:
    n = len(features)
    matrix = np.zeros((n, n), dtype=float)
    for i in range(n):
        for j in range(i + 1, n):
            matrix[i, j] = matrix[j, i] = 1.0 - compute_similarity(features[i], features[j], has_face, face_map)
    return matrix


def cluster_photos(
    features_list: list[ImageFeatures],
    face_analyses: dict[str, FaceAnalysis] | None = None,
    distance_threshold: float | None = None,
    pose_analyses: dict[str, PoseInfo] | None = None,
) -> list[PhotoGroup]:
    if not features_list:
        return []
    face_map = face_analyses or {}
    pose_map = pose_analyses or {}
    threshold = CLUSTER_THRESHOLD if distance_threshold is None else max(0.05, min(0.95, float(distance_threshold)))
    face_features = [feature for feature in features_list if feature.has_face or feature.face_count > 0]
    scene_features = [feature for feature in features_list if not (feature.has_face or feature.face_count > 0)]
    output: list[PhotoGroup] = []

    routes: list[tuple[list[ImageFeatures], bool, str | None]] = [(scene_features, False, None)]
    # A single-person route is split by body pose before visual clustering so
    # that sitting and standing sequences cannot be merged by background alone.
    single_person: dict[str, list[ImageFeatures]] = defaultdict(list)
    multi_person: dict[str, list[ImageFeatures]] = defaultdict(list)
    for feature in face_features:
        if feature.face_count == 1:
            pose = pose_map.get(feature.asset_id)
            single_person[(pose.body_pose if pose else "no_specific_pose")].append(feature)
        else:
            count = feature.face_count if feature.face_count < 4 else 4
            multi_person[f"people_{count}"].append(feature)
    routes.extend((items, True, key) for key, items in single_person.items())
    routes.extend((items, True, key) for key, items in multi_person.items())

    for route_features, has_face, pose_group_key in routes:
        if not route_features:
            continue
        route_features.sort(key=lambda item: item.timestamp or 0.0)
        matrix = _build_distance_matrix(route_features, face_map, has_face)
        allowed = None
        if has_face:
            allowed = np.ones((len(route_features), len(route_features)), dtype=bool)
            for i in range(len(route_features)):
                for j in range(i + 1, len(route_features)):
                    breakdown = compute_similarity_breakdown(route_features[i], route_features[j], True, face_map)
                    allowed[i, j] = allowed[j, i] = (
                        breakdown["face"] >= FACE_MATCH_MIN_SIMILARITY
                        and breakdown["context"] >= FACE_CONTEXT_MIN_SIMILARITY
                        and breakdown["face_count"] >= 0.5
                    )
        clusters = _average_linkage(list(range(len(route_features))), matrix, threshold, allowed)
        prefix = "face" if has_face else "scene"
        for number, cluster in enumerate(clusters, start=1):
            similarities = [1.0 - matrix[i, j] for pos, i in enumerate(cluster) for j in cluster[pos + 1:]]
            pair_breakdowns = [compute_similarity_breakdown(route_features[i], route_features[j], has_face, face_map) for pos, i in enumerate(cluster) for j in cluster[pos + 1:]]
            average_similarity = float(np.mean(similarities)) if similarities else 1.0
            times = [route_features[index].timestamp or 0.0 for index in cluster]
            reasons = ["有人脸相似度" if has_face else "视觉特征相似"]
            if len(cluster) > 1:
                reasons.append("组内平均链接距离受控")
            if has_face:
                if pose_group_key and pose_group_key.startswith("people_"):
                    reasons.append(f"按人脸数量归类: {pose_group_key}")
                else:
                    reasons.append(f"按身体姿势归类: {pose_group_key or 'no_specific_pose'}")
                reasons.append("整体背景 / 构图相似")
            else:
                reasons.append("颜色 / pHash / 纹理相近")
            semantic_values = [item.get("semantic") for item in pair_breakdowns if item.get("semantic_available")]
            semantic_models = sorted({
                model for item in pair_breakdowns for model in item.get("semantic_models", [])
            })
            output.append(PhotoGroup(
                group_id=f"{prefix}_{pose_group_key or 'all'}_{number:03d}",
                has_face=has_face,
                asset_ids=[route_features[index].asset_id for index in cluster],
                confidence=round(max(0.0, min(1.0, average_similarity)), 3),
                reasons=reasons,
                time_range=(min(times), max(times)),
                group_type=("portrait_people" if pose_group_key and pose_group_key.startswith("people_") else "pose") if has_face else "scene",
                average_similarity=round(average_similarity, 3),
                max_distance=round(float(max((matrix[i, j] for pos, i in enumerate(cluster) for j in cluster[pos + 1:]), default=0.0)), 3),
                participant_count=round(float(np.mean([route_features[index].face_count for index in cluster]))) if has_face else None,
                body_pose=pose_group_key if has_face and not (pose_group_key or "").startswith("people_") else None,
                semantic_available=bool(semantic_values),
                semantic_model=",".join(semantic_models) if semantic_models else None,
                semantic_similarity=round(float(np.mean(semantic_values)), 3) if semantic_values else 0.0,
                similarity_breakdown={key: round(float(np.mean([item[key] for item in pair_breakdowns if key in item])), 3) for key in ("phash", "color", "texture", "time", "face", "face_count", "context", "semantic") if any(key in item for item in pair_breakdowns)},
            ))

    output.sort(key=lambda group: group.time_range[0])
    return output
