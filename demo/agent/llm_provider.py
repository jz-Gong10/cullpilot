"""
模块5: 自然语言筛选策略解析 (LLM)

调用 OpenAI 兼容 API (SophNet 网关) 将用户自然语言转为结构化参数。
LLM 失败时自动降级到默认策略。
"""

from __future__ import annotations
import json
import os
import re
from pathlib import Path

from .schemas import ParsedStrategy, UserWeights
from .prompts import build_parse_messages, DEFAULT_STRATEGY
from .config import (
    LLM_MODEL, LLM_BASE_URL, LLM_TIMEOUT,
    LLM_MAX_RETRIES, LLM_TEMPERATURE,
)


def _get_api_key() -> str:
    """获取 API Key
    优先级: 环境变量 > .env 文件 > 空
    """
    # 1. 从环境变量
    key = os.environ.get("SOPHNET_API_KEY", "")

    # 2. 从 .env 文件读取本地算法设置。优先使用 python-dotenv，
    #    但 Demo 不应因为缺少这个可选包而完全读不到 key，因此保留手动兜底。
    if not key:
        env_path = Path(__file__).parent.parent / ".env"
        if env_path.exists():
            try:
                from dotenv import load_dotenv
                load_dotenv(env_path)
                key = os.environ.get("SOPHNET_API_KEY", "")
            except ImportError:
                # 只解析最简单的 KEY=VALUE 形式，不打印敏感值。
                for raw_line in env_path.read_text(encoding="utf-8").splitlines():
                    line = raw_line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    name, value = line.split("=", 1)
                    if name.strip() == "SOPHNET_API_KEY":
                        key = value.strip().strip('\"').strip("'")
                        break

    if not key:
        print("[LLM] 警告: 未配置 SOPHNET_API_KEY，使用降级模式")
        print("[LLM] 请在本地 .env 文件中设置: SOPHNET_API_KEY=你的密钥")
    return key


def parse_instruction(user_input: str) -> ParsedStrategy:
    """
    将用户自然语言筛选要求解析为结构化策略

    输入: 用户文字 (如 "每组保留两张，优先睁眼和清晰的照片")
    输出: ParsedStrategy 对象

    工作流程:
        1. 调用 LLM API
        2. 解析 JSON 响应
        3. 校验字段合法性
        4. 返回结构化的策略
        5. 失败时降级到默认策略
    """
    api_key = _get_api_key()
    if not api_key:
        return _local_parse(user_input, "API Key 未配置，使用本地规则")

    try:
        from openai import OpenAI

        client = OpenAI(
            base_url=LLM_BASE_URL,
            api_key=api_key,
            timeout=LLM_TIMEOUT,
        )

        messages = build_parse_messages(user_input)

        for attempt in range(LLM_MAX_RETRIES + 1):
            try:
                response = client.chat.completions.create(
                    model=LLM_MODEL,
                    messages=messages,
                    temperature=LLM_TEMPERATURE,
                    max_tokens=1024,
                )
                content = response.choices[0].message.content
                if not content:
                    continue

                # 尝试提取 JSON
                data = _extract_json(content)
                if data is None:
                    continue

                return _validate_and_build(data)

            except Exception as e:
                if attempt < LLM_MAX_RETRIES:
                    continue
                print(f"[LLM] 调用失败 (重试{attempt}次): {e}")
                return _local_parse(user_input, f"LLM 调用失败，使用本地规则: {e}")

        return _local_parse(user_input, "LLM 无返回，使用本地规则")

    except ImportError:
        print("[LLM] openai 库未安装，使用默认参数")
        return _local_parse(user_input, "openai 库未安装，使用本地规则")
    except Exception as e:
        print(f"[LLM] 未知错误: {e}")
        return _local_parse(user_input, f"LLM 初始化失败，使用本地规则: {e}")


def _extract_json(content: str) -> dict | None:
    """从 LLM 回复中提取 JSON"""
    content = content.strip()

    # 尝试直接解析
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        pass

    # 尝试提取 markdown 代码块中的 JSON
    import re
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    # 尝试找到第一个 { 和最后一个 }
    start = content.find("{")
    end = content.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(content[start:end + 1])
        except json.JSONDecodeError:
            pass

    return None


def _validate_and_build(data: dict) -> ParsedStrategy:
    """校验并构建 ParsedStrategy"""
    strategy = ParsedStrategy()

    # keep_per_group
    kpg = data.get("keep_per_group")
    if kpg is not None and isinstance(kpg, (int, float)):
        strategy.keep_per_group = max(1, min(10, int(kpg)))

    # strictness
    strictness = data.get("strictness")
    if strictness in ("loose", "standard", "strict"):
        strategy.strictness = strictness

    # weights
    weights_data = data.get("weights", {})
    if weights_data and isinstance(weights_data, dict):
        strategy.weights = UserWeights(
            sharpness=float(weights_data.get("sharpness", 0.25)),
            eyes_open=float(weights_data.get("eyes_open", 0.25)),
            expression=float(weights_data.get("expression", 0.20)),
            exposure=float(weights_data.get("exposure", 0.15)),
            composition=float(weights_data.get("composition", 0.15)),
        )

    # Numeric and boolean filters are intentionally whitelisted.
    for field_name in (
        "min_quality", "min_sharpness", "max_highlight_clip",
        "max_shadow_clip", "min_face_completeness",
    ):
        value = data.get(field_name)
        if isinstance(value, (int, float)):
            setattr(strategy, field_name, max(0.0, min(1.0, float(value))))
    for field_name in (
        "require_face", "require_eyes_open", "require_frontal", "require_smile",
    ):
        value = data.get(field_name)
        if isinstance(value, bool):
            setattr(strategy, field_name, value)

    valid_body = {"standing", "sitting", "squat_or_kneel", "no_specific_pose"}
    valid_hand = {"no_specific_hand_action", "raised_hand", "peace_sign", "hand_near_face"}
    body_aliases = {
        "站立": "standing", "站着": "standing", "坐姿": "sitting", "坐着": "sitting",
        "蹲下": "squat_or_kneel", "蹲姿": "squat_or_kneel", "跪坐": "squat_or_kneel",
        "无特定姿态": "no_specific_pose",
    }
    hand_aliases = {
        "无特定手势": "no_specific_hand_action", "抬手": "raised_hand", "挥手": "raised_hand",
        "比耶": "peace_sign", "剪刀手": "peace_sign", "托脸": "hand_near_face", "手扶脸": "hand_near_face",
    }
    for field_name, allowed in (("body_poses", valid_body), ("hand_actions", valid_hand)):
        value = data.get(field_name)
        if value is None:
            value = data.get("require_body_pose" if field_name == "body_poses" else "require_hand_action")
        if isinstance(value, str):
            value = [value]
        if isinstance(value, list):
            aliases = body_aliases if field_name == "body_poses" else hand_aliases
            values = [aliases.get(str(item), str(item)) for item in value]
            values = [item for item in values if item in allowed]
            if values:
                setattr(strategy, field_name, list(dict.fromkeys(values)))

    # constraints (legacy aliases are retained for compatibility)
    constraints_data = data.get("constraints", {})
    if constraints_data and isinstance(constraints_data, dict):
        for k, v in constraints_data.items():
            if isinstance(v, bool):
                strategy.constraints[k] = v

    # unsupported_terms
    ut = data.get("unsupported_terms", [])
    if isinstance(ut, list):
        strategy.unsupported_terms = [str(x) for x in ut]

    # explanation
    exp = data.get("explanation", "")
    strategy.explanation = str(exp) if exp else "解析完成"

    return strategy


def _local_parse(user_input: str, reason: str) -> ParsedStrategy:
    """Deterministic fallback so the Demo remains testable without network."""
    text = user_input or ""
    strategy = ParsedStrategy(
        keep_per_group=2,
        strictness="standard",
        weights=UserWeights(),
        explanation="使用本地规则解析",
    )
    match = re.search(r"每组\s*(?:保留|选|选择)\s*(\d+)\s*张", text)
    if match:
        strategy.keep_per_group = max(1, min(10, int(match.group(1))))
    if any(term in text for term in ("只看人物", "有人脸", "含人脸")):
        strategy.require_face = True
    elif any(term in text for term in ("只看风景", "无人脸", "风景照")):
        strategy.require_face = False
    if any(term in text for term in ("睁眼", "眼睛张开")):
        strategy.require_eyes_open = True
        strategy.weights.eyes_open += 0.20
    if any(term in text for term in ("正面", "正脸")):
        strategy.require_frontal = True
        strategy.weights.composition += 0.10
    if any(term in text for term in ("微笑", "笑容")):
        strategy.require_smile = True
        strategy.weights.expression += 0.15
    if any(term in text for term in ("清晰", "不模糊")):
        strategy.min_sharpness = 0.35
        strategy.weights.sharpness += 0.20
    if any(term in text for term in ("过曝", "高光")):
        strategy.max_highlight_clip = 0.15
    if any(term in text for term in ("欠曝", "阴影")):
        strategy.max_shadow_clip = 0.15
    body_aliases = {
        "standing": ("站立", "站着"),
        "sitting": ("坐姿", "坐着", "坐下"),
        "squat_or_kneel": ("蹲下", "蹲姿", "跪坐", "跪着"),
        "no_specific_pose": ("无特定姿态", "没有特定姿态"),
    }
    hand_aliases = {
        "raised_hand": ("抬手", "挥手"),
        "peace_sign": ("比耶", "剪刀手"),
        "hand_near_face": ("托脸", "手扶脸", "扶脸"),
        "no_specific_hand_action": ("无特定手势", "没有特定手势"),
    }
    for key, terms in body_aliases.items():
        if any(term in text for term in terms):
            strategy.body_poses = [key]
            break
    for key, terms in hand_aliases.items():
        if any(term in text for term in terms):
            strategy.hand_actions = [key]
            break
    unsupported = [term for term in ("高级感", "故事感", "氛围感", "电影感", "审美") if term in text]
    strategy.unsupported_terms = unsupported
    strategy.weights = strategy.weights.normalized()
    strategy.explanation = f"{strategy.explanation}：每组保留{strategy.keep_per_group}张"
    if strategy.body_poses:
        strategy.explanation += f"，身体姿势限定为 {','.join(strategy.body_poses)}"
    if strategy.hand_actions:
        strategy.explanation += f"，手部动作限定为 {','.join(strategy.hand_actions)}"
    if reason:
        strategy.explanation += f"（{reason}）"
    return strategy


def _fallback(reason: str) -> ParsedStrategy:
    """LLM 失败时的降级策略"""
    d = DEFAULT_STRATEGY
    return ParsedStrategy(
        keep_per_group=d["keep_per_group"],
        strictness=d["strictness"],
        weights=UserWeights(**d["weights"]),
        constraints=d["constraints"],
        body_poses=d.get("body_poses"),
        hand_actions=d.get("hand_actions"),
        unsupported_terms=[],
        explanation=f"{d['explanation']} (原因: {reason})",
    )
