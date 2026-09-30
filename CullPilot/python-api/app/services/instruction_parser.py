"""Small, deterministic parser for user screening requirements.

The parser intentionally covers the measurable controls that the current
scorer understands.  It keeps the API useful without requiring an external
LLM or a CLIP checkpoint; a model provider can replace this module later.
"""

from __future__ import annotations

import re

from app.schemas.llm import ParseInstructionResponse, SelectionStrategy


DEFAULT_WEIGHTS = {
    "sharpness": 0.30,
    "eyesOpen": 0.25,
    "expression": 0.15,
    "exposure": 0.15,
    "composition": 0.10,
    "motion": 0.05,
}
DEFAULT_CONSTRAINTS = {
    "avoidSevereBlur": True,
    "avoidSevereOverexposure": True,
    "allowMildMotionBlur": True,
    "preferFrontFacing": False,
}

FEATURE_TERMS = {
    "sharpness": ("清晰", "清楚", "锐利", "不模糊", "sharp", "clear"),
    "eyesOpen": ("睁眼", "眼睛睁开", "眼神", "eyes open"),
    "expression": ("表情", "微笑", "笑容", "自然", "expression", "smile"),
    "exposure": ("曝光", "亮度", "明暗", "过曝", "exposure"),
    "composition": ("构图", "主体完整", "完整", "composition"),
    "motion": ("动态清晰", "运动模糊", "抓拍", "motion"),
}


def _normalize(weights: dict[str, float]) -> dict[str, float]:
    total = sum(weights.values())
    if total <= 0:
        return dict(DEFAULT_WEIGHTS)
    return {key: round(value / total, 4) for key, value in weights.items() if value > 0}


def _keep_count(text: str) -> int:
    match = re.search(r"(?:保留|选|留)\s*(\d+)\s*(?:张|个|幅)?", text)
    if not match:
        return 2
    return max(1, min(10, int(match.group(1))))


def _has_keep_count(text: str) -> bool:
    return re.search(r"(?:保留|选|留)\s*\d+\s*(?:张|个|幅)?", text) is not None


def parse_instruction(text: str, allowed_features: list[str] | None = None) -> ParseInstructionResponse:
    text = (text or "").strip()
    allowed = set(allowed_features or [])
    if not text:
        return ParseInstructionResponse(
            strategy=SelectionStrategy(
                keep_per_group=2,
                strictness="standard",
                content_mode="auto",
                weights={},
                constraints={},
                explanation="未输入筛选要求，将根据图片类型自动使用人像或风景的默认参数。",
            ),
            confidence=1.0,
            fallback_used=True,
            provider="builtin",
            model="rule-based-defaults",
        )

    weights = dict(DEFAULT_WEIGHTS)
    matched: list[str] = []
    for feature, terms in FEATURE_TERMS.items():
        if any(term in text.lower() for term in terms):
            matched.append(feature)
            if feature in allowed or not allowed:
                weights[feature] += 0.35

    constraints = dict(DEFAULT_CONSTRAINTS)
    constraint_matched = False
    if any(term in text for term in ("正脸", "正面", "front-facing")):
        constraints["preferFrontFacing"] = True
        constraint_matched = True
    if any(term in text for term in ("允许轻微模糊", "轻微模糊可以", "allow mild blur")):
        constraints["allowMildMotionBlur"] = True
        constraint_matched = True
    if any(term in text for term in ("不要模糊", "避免模糊", "不清晰不要", "no blur")):
        constraints["avoidSevereBlur"] = True
        constraint_matched = True
    if any(term in text for term in ("不要过曝", "避免过曝", "no overexposure")):
        constraints["avoidSevereOverexposure"] = True
        constraint_matched = True

    if any(term in text for term in ("严格", "宁缺毋滥", "strict")):
        strictness = "strict"
    elif any(term in text for term in ("宽松", "多保留", "loose")):
        strictness = "loose"
    else:
        strictness = "standard"

    if any(term in text for term in ("人像", "人物", "肖像", "portrait")):
        content_mode = "portrait"
    elif any(term in text for term in ("风景", "景物", "风光", "landscape")):
        content_mode = "landscape"
    elif any(term in text for term in ("混合", "人像和风景", "mixed")):
        content_mode = "mixed"
    else:
        content_mode = "auto"

    content_matched = content_mode != "auto"
    recognized = bool(matched or constraint_matched or content_matched or _has_keep_count(text))
    unsupported_terms = []
    if not recognized:
        unsupported_terms.append("未识别的筛选描述")
    confidence = 0.55 + min(0.4, len(matched) * 0.1)
    explanation = (
        "已将用户要求映射为可计算的清晰度、曝光、人物状态和构图指标。"
        if recognized
        else "未识别到当前算法支持的质量指标，将使用默认参数。"
    )
    return ParseInstructionResponse(
        strategy=SelectionStrategy(
            keep_per_group=_keep_count(text),
            strictness=strictness,
            content_mode=content_mode,
            weights=_normalize(weights) if matched else {},
            constraints=constraints if constraint_matched else {},
            unsupported_terms=unsupported_terms,
            explanation=explanation,
        ),
        confidence=round(confidence if recognized else 0.35, 2),
        fallback_used=not recognized,
        provider="builtin",
        model="rule-based-v1",
    )
