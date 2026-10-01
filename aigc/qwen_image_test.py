#!/usr/bin/env python3
"""用目录中的图片调用千问图像编辑 API，并保存本地测试结果。

安装依赖: python -m pip install requests

最小用法（PowerShell）:
    $env:DASHSCOPE_API_KEY = "sk-..."
    python .\qwen_image_test.py

默认调用 DashScope 同步多模态接口。可用 DASHSCOPE_BASE_URL 覆盖完整接口地址，
或用 DASHSCOPE_WORKSPACE_ID + DASHSCOPE_REGION 拼出业务空间域名。
"""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


DEFAULT_ENDPOINT = (
    "https://dashscope.aliyuncs.com/api/v1/services/aigc/"
    "multimodal-generation/generation"
)
DEFAULT_PROMPT = (
    "把右上角的蛋挞去掉，让图片里只留下两个蛋挞"
)
REGION_HOSTS = {
    "cn-beijing": "cn-beijing.maas.aliyuncs.com",
    "ap-southeast-1": "ap-southeast-1.maas.aliyuncs.com",
    "us-east-1": "us-east-1.maas.aliyuncs.com",
    "eu-central-1": "eu-central-1.maas.aliyuncs.com",
    "ap-northeast-1": "ap-northeast-1.maas.aliyuncs.com",
    "cn-hongkong": "cn-hongkong.maas.aliyuncs.com",
}


def endpoint_from_env() -> str:
    """Resolve the API endpoint without exposing credentials in output."""
    explicit = os.getenv("DASHSCOPE_BASE_URL") or os.getenv("QWEN_IMAGE_ENDPOINT")
    if explicit:
        return explicit.rstrip("/")

    workspace = os.getenv("DASHSCOPE_WORKSPACE_ID")
    if workspace:
        region = os.getenv("DASHSCOPE_REGION", "cn-beijing")
        host = REGION_HOSTS.get(region)
        if not host:
            raise ValueError(
                f"不支持的 DASHSCOPE_REGION={region!r}，请设置 DASHSCOPE_BASE_URL。"
            )
        return f"https://{workspace}.{host}/api/v1/services/aigc/multimodal-generation/generation"
    return DEFAULT_ENDPOINT


def image_data_uri(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"找不到输入图片: {path}")
    size = path.stat().st_size
    if size > 10 * 1024 * 1024:
        raise ValueError(f"输入图片超过 10 MB 限制: {size} bytes")
    mime, _ = mimetypes.guess_type(path.name)
    if not mime or not mime.startswith("image/"):
        raise ValueError(f"无法识别图片格式: {path.suffix}")
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def redact_request(payload: dict[str, Any], image_size: int) -> dict[str, Any]:
    """Keep request metadata useful while avoiding a huge base64 dump in logs."""
    result = json.loads(json.dumps(payload, ensure_ascii=False))
    try:
        content = result["input"]["messages"][0]["content"]
        for item in content:
            if "image" in item:
                item["image"] = f"<base64 image omitted; {image_size} bytes>"
    except (KeyError, IndexError, TypeError):
        pass
    return result


def extract_image_urls(value: Any) -> list[str]:
    """Find image URLs in the documented choices/content response shape."""
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"image", "url"} and isinstance(child, str) and child.startswith(("http://", "https://")):
                found.append(child)
            else:
                found.extend(extract_image_urls(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(extract_image_urls(child))
    return list(dict.fromkeys(found))


def save_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    api_key = "sk-ws-H.PLPDMDI.gguN.MEQCIAbdcUqJeGAYVkJPwMMWq_IfjpMG4CbBDMWTuRo6rp0fAiBVBBouWHgidCfUw8-bBJAhtE_SQKGLzAi0gZprRIKcWQ"
    if not api_key and not args.dry_run:
        print("未找到 DASHSCOPE_API_KEY（或 QWEN_API_KEY）环境变量。", file=sys.stderr)
        print("PowerShell 示例: $env:DASHSCOPE_API_KEY = 'sk-...'; python .\\qwen_image_test.py", file=sys.stderr)
        return 2

    image_path = Path(args.image).expanduser().resolve()
    try:
        data_uri = image_data_uri(image_path)
        endpoint = args.endpoint or endpoint_from_env()
    except (OSError, ValueError) as exc:
        print(f"输入配置错误: {exc}", file=sys.stderr)
        return 2

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    result_dir = Path(args.output_dir).expanduser().resolve() / timestamp
    result_dir.mkdir(parents=True, exist_ok=True)

    payload: dict[str, Any] = {
        "model": args.model,
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [{"image": data_uri}, {"text": args.prompt}],
                }
            ]
        },
        "parameters": {
            "prompt_extend": args.prompt_extend,
            "size": args.size,
            "n": args.n,
            "watermark": args.watermark,
        },
    }
    save_json(result_dir / "request.json", redact_request(payload, image_path.stat().st_size))
    (result_dir / "input_image_path.txt").write_text(str(image_path), encoding="utf-8")

    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    response_info: dict[str, Any] = {
        "endpoint": endpoint,
        "http_status": None,
        "response": None,
        "downloaded_files": [],
        "download_errors": [],
    }
    if args.dry_run:
        response_info["response"] = {
            "dry_run": True,
            "message": "未发送网络请求；已完成图片读取、Base64 编码和脱敏请求保存。",
        }
        save_json(result_dir / "response.json", response_info)
        summary = {
            "success": True,
            "dry_run": True,
            "result_dir": str(result_dir),
            "image_urls_found": 0,
            "downloaded_files": [],
            "download_errors": [],
        }
        save_json(result_dir / "summary.json", summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    try:
        response = requests.post(endpoint, headers=headers, json=payload, timeout=args.timeout)
        response_info["http_status"] = response.status_code
        try:
            response_info["response"] = response.json()
        except ValueError:
            response_info["response"] = {"raw_text": response.text}
        save_json(result_dir / "response.json", response_info)
        response.raise_for_status()
    except requests.RequestException as exc:
        response_info["request_error"] = str(exc)
        save_json(result_dir / "response.json", response_info)
        print(f"API 请求失败，详情已保存到 {result_dir}: {exc}", file=sys.stderr)
        return 1

    urls = extract_image_urls(response_info["response"])
    for index, url in enumerate(urls, start=1):
        output_path = result_dir / f"generated_{index}.png"
        try:
            image_response = requests.get(url, timeout=args.timeout, stream=True)
            image_response.raise_for_status()
            content_type = image_response.headers.get("Content-Type", "")
            suffix = ".jpg" if "jpeg" in content_type else ".png"
            output_path = output_path.with_suffix(suffix)
            with output_path.open("wb") as output_file:
                for chunk in image_response.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        output_file.write(chunk)
            response_info["downloaded_files"].append(str(output_path))
        except requests.RequestException as exc:
            response_info["download_errors"].append({"url": url, "error": str(exc)})

    save_json(result_dir / "response.json", response_info)
    summary = {
        "success": response_info["http_status"] == 200 and bool(response_info["downloaded_files"]),
        "result_dir": str(result_dir),
        "image_urls_found": len(urls),
        "downloaded_files": response_info["downloaded_files"],
        "download_errors": response_info["download_errors"],
    }
    save_json(result_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["success"] else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="用本地图片测试千问图像编辑 API")
    parser.add_argument("--image", default="001.jpg", help="输入图片路径，默认 001.jpg")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="图像编辑提示词")
    parser.add_argument("--model", default="qwen-image-2.0")
    parser.add_argument("--size", default="1024*1024", help="输出尺寸，例如 1024*1024")
    parser.add_argument("--n", type=int, default=1, choices=range(1, 7), metavar="1-6")
    parser.add_argument("--prompt-extend", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--watermark", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--endpoint", help="完整接口地址，优先级高于环境变量")
    parser.add_argument("--output-dir", default="outputs", help="结果根目录，默认 outputs")
    parser.add_argument("--timeout", type=float, default=180, help="单次 HTTP 超时秒数")
    parser.add_argument("--dry-run", action="store_true", help="只生成脱敏请求和摘要，不发送网络请求")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
