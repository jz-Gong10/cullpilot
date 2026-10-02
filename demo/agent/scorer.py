"""Group recommendation with filters, weighted scoring and MMR."""

from __future__ import annotations

import numpy as np

from .clustering import _color_distance, _phash_distance
from .config import DEFAULT_KEEP_COUNT, DEFAULT_STRICTNESS, MMR_LAMBDA, STRICTNESS_THRESHOLDS
from .schemas import FaceAnalysis, GroupRecommendation, ImageFeatures, PhotoGroup, PhotoRank, PoseInfo, QualityScore, UserWeights


def _face_values(face: FaceAnalysis | None) -> tuple[float, float, float, float]:
    if not face or not face.faces:
        return 0.5, 0.5, 0.5, 0.5
    eyes = [1.0 if item.eyes_open else 0.0 for item in face.faces if item.eyes_open is not None]
    expression = [1.0 if item.is_smiling or item.emotion == "happy" else 0.5 for item in face.faces]
    completeness = [item.face_completeness for item in face.faces]
    frontal = [1.0 if item.is_frontal else 0.0 for item in face.faces]
    return (
        float(np.mean(eyes)) if eyes else 0.5,
        float(np.mean(expression)) if expression else 0.5,
        float(np.mean(completeness)) if completeness else 0.5,
        float(np.mean(frontal)) if frontal else 0.5,
    )


def _filter_photo(feature: ImageFeatures, quality: QualityScore, face: FaceAnalysis | None, filters: dict | None, pose: PoseInfo | None = None) -> list[str]:
    filters = filters or {}
    reasons = []
    if filters.get("min_quality") is not None and quality.overall_score < float(filters["min_quality"]):
        reasons.append("综合质量低于筛选阈值")
    if filters.get("min_sharpness") is not None:
        sharpness_floor = float(filters["min_sharpness"])
        blur_quality_floor = float(filters.get("blur_quality_floor", 0.60))

        # Only treat blur as severe when both sharpness and overall quality are low.
        if feature.sharpness < sharpness_floor and quality.overall_score < blur_quality_floor:
            reasons.append("清晰度与综合质量均低于底线")
    if filters.get("max_highlight_clip") is not None and feature.highlight_clip > float(filters["max_highlight_clip"]):
        reasons.append("过曝比例超过筛选阈值")
    if filters.get("max_shadow_clip") is not None and feature.shadow_clip > float(filters["max_shadow_clip"]):
        reasons.append("欠曝比例超过筛选阈值")
    if filters.get("require_face") is True and not feature.face_count:
        reasons.append("要求有人脸")
    if filters.get("require_face") is False and feature.face_count:
        reasons.append("要求无人脸")
    eyes, _, completeness, frontal = _face_values(face)
    if filters.get("require_eyes_open") is True and eyes < 0.999:
        reasons.append("存在闭眼风险")
    if filters.get("require_frontal") is True and frontal < 0.999:
        reasons.append("存在非正面人脸")
    if filters.get("require_smile") is True and _face_values(face)[1] < 0.75:
        reasons.append("未检测到足够明显的微笑")
    if filters.get("min_face_completeness") is not None and completeness < float(filters["min_face_completeness"]):
        reasons.append("人脸完整度不足")
    body_poses = filters.get("body_poses") or []
    if body_poses and (pose is None or pose.body_pose not in body_poses):
        reasons.append("身体姿势不符合筛选条件")
    hand_actions = filters.get("hand_actions") or []
    if hand_actions and (pose is None or pose.hand_action not in hand_actions):
        reasons.append("手部动作不符合筛选条件")
    return reasons


def _score_item(feature: ImageFeatures, quality: QualityScore, face: FaceAnalysis | None, weights: UserWeights) -> tuple[float, list[str]]:
    weights = weights.normalized()
    eyes, expression, completeness, frontal = _face_values(face)
    if feature.face_count:
        dimensions = {
            "sharpness": quality.sharpness_score,
            "eyes_open": eyes,
            "expression": expression,
            "exposure": quality.exposure_score,
            "composition": (completeness + frontal) / 2,
            "face_completeness": completeness,
            "frontal": frontal,
        }
        score = sum(getattr(weights, key, 0.0) * value for key, value in dimensions.items())
        reasons = []
        if quality.sharpness_score >= 0.65:
            reasons.append("清晰度较高")
        if eyes >= 0.8:
            reasons.append("人物双眼状态良好")
        if expression >= 0.75:
            reasons.append("表情更自然")
        if completeness >= 0.8:
            reasons.append("人物主体完整")
        return float(max(0.0, min(1.0, score))), reasons

    dimensions = {
        "sharpness": quality.sharpness_score,
        "exposure": quality.exposure_score,
        "contrast": quality.contrast_score,
        "shadow": quality.shadow_score,
        "highlight": quality.highlight_score,
        "composition": quality.composition_score,
    }
    # Portrait-only controls (eyes/expression) should not silently consume
    # landscape score mass. Re-normalize the dimensions that apply here.
    applicable = {key: getattr(weights, key, 0.0) for key in dimensions}
    total = sum(applicable.values())
    if total <= 1e-9:
        applicable = {"sharpness": 0.35, "exposure": 0.20, "contrast": 0.15, "shadow": 0.15, "highlight": 0.15}
        total = 1.0
    score = sum((applicable[key] / total) * value for key, value in dimensions.items())
    reasons = []
    if quality.sharpness_score >= 0.65:
        reasons.append("清晰度较高")
    if quality.exposure_score >= 0.70:
        reasons.append("曝光控制良好")
    if quality.contrast_score >= 0.50:
        reasons.append("对比度适中")
    if quality.shadow_score >= 0.70:
        reasons.append("阴影细节保留较好")
    return float(max(0.0, min(1.0, score))), reasons


def _duplicate_similarity(a: ImageFeatures, b: ImageFeatures) -> float:
    phash = 1.0 - _phash_distance(a.phash, b.phash)
    color = 1.0 - _color_distance(a.color_histogram, b.color_histogram)
    layout = 1.0 - min(1.0, abs(a.face_count - b.face_count) / max(a.face_count, b.face_count, 1))
    return float(max(0.0, min(1.0, 0.5 * phash + 0.3 * color + 0.2 * layout)))


def recommend_group(group: PhotoGroup, features: dict[str, ImageFeatures], qualities: dict[str, QualityScore], faces: dict[str, FaceAnalysis], weights: UserWeights | None = None, keep_count: int = DEFAULT_KEEP_COUNT, strictness: str = DEFAULT_STRICTNESS, filters: dict | None = None, poses: dict[str, PoseInfo] | None = None) -> GroupRecommendation:
    weights = weights or UserWeights()
    _, discard_threshold = STRICTNESS_THRESHOLDS.get(strictness, STRICTNESS_THRESHOLDS[DEFAULT_STRICTNESS])
    candidates, filtered = [], []
    for asset_id in group.asset_ids:
        feature, quality = features.get(asset_id), qualities.get(asset_id)
        if feature is None or quality is None:
            continue
        face = faces.get(asset_id)
        pose = (poses or {}).get(asset_id)
        filter_reasons = _filter_photo(feature, quality, face, filters, pose)
        score, reasons = _score_item(feature, quality, face, weights)
        item = (asset_id, score, reasons, filter_reasons)
        (filtered if filter_reasons else candidates).append(item)

    candidates.sort(key=lambda item: item[1], reverse=True)
    selected = []
    remaining = list(candidates)
    target = max(1, min(int(keep_count), len(candidates))) if candidates else 0
    if remaining:
        selected.append(remaining.pop(0))
    while remaining and len(selected) < target:
        best_index, best_value = 0, -float("inf")
        for index, item in enumerate(remaining):
            similarity = max(_duplicate_similarity(features[item[0]], features[chosen[0]]) for chosen in selected)
            value = MMR_LAMBDA * item[1] - (1.0 - MMR_LAMBDA) * similarity
            if value > best_value:
                best_value, best_index = value, index
        selected.append(remaining.pop(best_index))

    selected_ids = {item[0] for item in selected}
    ordered = selected + remaining + filtered
    ranks = []
    for index, (asset_id, score, reasons, filter_reasons) in enumerate(ordered, start=1):
        filtered_out = bool(filter_reasons)
        if filtered_out:
            state = "filtered_out"
        elif asset_id in selected_ids:
            # keep_count is an explicit per-group selection count. Quality
            # thresholds still classify lower-ranked photos, but must not
            # reduce the requested number of top-ranked picks in a group.
            state = "keep"
        elif score < discard_threshold:
            state = "discard_suggested"
        else:
            state = "undecided"
        if index > 1 and asset_id in selected_ids:
            reasons = list(reasons) + ["与已选照片重复度较低"]
        ranks.append(PhotoRank(asset_id, index, round(score, 3), state, reasons[:6], filtered_out, filter_reasons))
    return GroupRecommendation(group.group_id, keep_count, strictness, ranks)


def batch_recommend(groups: dict[str, PhotoGroup] | list[PhotoGroup], features: dict[str, ImageFeatures], qualities: dict[str, QualityScore], faces: dict[str, FaceAnalysis], weights: UserWeights | None = None, keep_count: int = DEFAULT_KEEP_COUNT, strictness: str = DEFAULT_STRICTNESS, filters: dict | None = None, poses: dict[str, PoseInfo] | None = None) -> dict[str, GroupRecommendation]:
    group_map = groups if isinstance(groups, dict) else {group.group_id: group for group in groups}
    return {
        group_id: recommend_group(group, features, qualities, faces, weights, keep_count, strictness, filters, poses)
        for group_id, group in group_map.items()
    }
