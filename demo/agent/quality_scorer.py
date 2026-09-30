"""Separate portrait and landscape quality scoring."""

from __future__ import annotations

import numpy as np

from .config import BRIGHTNESS_WELL_EXPOSED
from .schemas import FaceAnalysis, ImageFeatures, QualityScore


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _exposure(features: ImageFeatures) -> tuple[float, list[str], list[str]]:
    highlight = _clamp(1.0 - min(1.0, features.highlight_clip / 0.25))
    shadow = _clamp(1.0 - min(1.0, features.shadow_clip / 0.25))
    low, high = BRIGHTNESS_WELL_EXPOSED
    brightness = _clamp(1.0 - (max(0.0, low - features.brightness) + max(0.0, features.brightness - high)) * 2.0)
    score = 0.45 * highlight + 0.35 * shadow + 0.20 * brightness
    reasons, warnings = [], []
    if score >= 0.70:
        reasons.append("曝光控制良好")
    if features.highlight_clip > 0.05:
        warnings.append(f"高光过曝约 {features.highlight_clip * 100:.1f}%")
    if features.shadow_clip > 0.05:
        warnings.append(f"阴影欠曝约 {features.shadow_clip * 100:.1f}%")
    if features.brightness < low:
        warnings.append("整体偏暗")
    elif features.brightness > high:
        warnings.append("整体偏亮")
    return score, reasons, warnings


def _face_dimensions(face_analysis: FaceAnalysis) -> tuple[float, float, float, float]:
    faces = face_analysis.faces
    if not faces:
        return 0.5, 0.5, 0.5, 0.5
    valid_eyes = [1.0 if face.eyes_open else 0.0 for face in faces if face.eyes_open is not None]
    valid_expression = [1.0 if face.is_smiling or face.emotion == "happy" else 0.5 for face in faces]
    completeness = [face.face_completeness for face in faces]
    frontal = [1.0 if face.is_frontal else 0.0 for face in faces]
    return (
        float(np.mean(valid_eyes)) if valid_eyes else 0.5,
        float(np.mean(valid_expression)) if valid_expression else 0.5,
        float(np.mean(completeness)) if completeness else 0.5,
        float(np.mean(frontal)) if frontal else 0.5,
    )


def score_quality(features: ImageFeatures, face_analysis: FaceAnalysis | None = None, face_score: float | None = None) -> QualityScore:
    """Score one image; the old ``face_score`` argument remains compatible."""
    exposure, exposure_reasons, exposure_warnings = _exposure(features)
    sharpness = _clamp(features.sharpness)
    contrast = _clamp(features.contrast)
    shadow_score = _clamp(1.0 - min(1.0, features.shadow_clip / 0.25))
    highlight_score = _clamp(1.0 - min(1.0, features.highlight_clip / 0.25))
    reasons = list(exposure_reasons)
    warnings = list(exposure_warnings)

    if sharpness >= 0.65:
        reasons.append("清晰度较高")
    elif sharpness < 0.30:
        warnings.append("清晰度偏低，可能存在失焦或运动模糊")
    if contrast < 0.25:
        warnings.append("对比度偏低")

    has_face = bool(features.face_count or features.has_face)
    if has_face:
        analysis = face_analysis or FaceAnalysis(asset_id=features.asset_id, face_count=features.face_count)
        eyes, expression, completeness, frontal = _face_dimensions(analysis)
        overall = (
            sharpness * 0.20
            + exposure * 0.20
            + eyes * 0.20
            + expression * 0.15
            + completeness * 0.15
            + frontal * 0.10
        )
        mode = "portrait"
        reasons.append(f"检测到 {features.face_count} 张人脸")
        if eyes >= 0.8:
            reasons.append("人物双眼状态良好")
        elif eyes < 0.5:
            warnings.append("存在闭眼风险")
        if completeness < 0.7:
            warnings.append("部分人物主体靠近画面边缘")
        return QualityScore(
            asset_id=features.asset_id,
            overall_score=round(_clamp(overall), 3),
            confidence=0.75 if analysis.faces else 0.55,
            mode=mode,
            sharpness_score=round(sharpness, 3),
            exposure_score=round(exposure, 3),
            contrast_score=round(contrast, 3),
            shadow_score=round(shadow_score, 3),
            highlight_score=round(highlight_score, 3),
            eyes_open_score=round(eyes, 3),
            expression_score=round(expression, 3),
            composition_score=round((completeness + frontal) / 2, 3),
            completeness_score=round(completeness, 3),
            frontal_score=round(frontal, 3),
            face_score=round(eyes, 3),
            reasons=reasons[:8],
            warnings=warnings[:6],
        )

    overall = (
        sharpness * 0.35
        + exposure * 0.20
        + contrast * 0.15
        + shadow_score * 0.15
        + highlight_score * 0.15
    )
    reasons.append("风景 / 无人脸评分")
    return QualityScore(
        asset_id=features.asset_id,
        overall_score=round(_clamp(overall), 3),
        confidence=0.85,
        mode="landscape",
        sharpness_score=round(sharpness, 3),
        exposure_score=round(exposure, 3),
        contrast_score=round(contrast, 3),
        shadow_score=round(shadow_score, 3),
        highlight_score=round(highlight_score, 3),
        composition_score=round(contrast, 3),
        reasons=reasons[:8],
        warnings=warnings[:6],
    )


def batch_score(features_list: list[ImageFeatures], faces: dict[str, FaceAnalysis] | None = None, face_scores: dict[str, float] | None = None) -> dict[str, QualityScore]:
    faces = faces or {}
    return {
        feature.asset_id: score_quality(feature, faces.get(feature.asset_id), (face_scores or {}).get(feature.asset_id))
        for feature in features_list
    }
