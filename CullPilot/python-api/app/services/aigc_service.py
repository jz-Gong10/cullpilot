import base64
import mimetypes
import os
from pathlib import Path
from typing import Any

import httpx

from app.config import settings
from app.schemas.aigc import AigcEditRequest, AigcEditResponse


DEFAULT_BEAUTIFY_PROMPT = (
    "Naturally beautify the input image. Improve exposure, color, clarity, "
    "and overall polish. Preserve the identity, main subject, composition, "
    "and realistic details of the original image. Do not add unrelated "
    "content or change the original intent."
)


class AigcProviderError(RuntimeError):
    pass


def edit_image(request: AigcEditRequest) -> AigcEditResponse:
    source = _resolve_source(request.source_path)
    source_uri = _image_data_uri(source)
    prompt = request.prompt.strip() or DEFAULT_BEAUTIFY_PROMPT
    model = request.model.strip() if request.model and request.model.strip() else settings.aigc_model
    api_key = settings.aigc_api_key or os.getenv("DASHSCOPE_API_KEY", "")
    if not api_key:
        raise AigcProviderError("AIGC provider API key is not configured")

    payload: dict[str, Any] = {
        "model": model,
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [{"image": source_uri}, {"text": prompt}],
                }
            ]
        },
        "parameters": {
            "prompt_extend": request.prompt_extend,
            "size": request.size,
            "n": 1,
            "watermark": request.watermark,
        },
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    try:
        with httpx.Client(timeout=settings.aigc_timeout_seconds) as client:
            response = client.post(settings.aigc_endpoint, headers=headers, json=payload)
            response.raise_for_status()
            response_json = response.json()
            image_url = _extract_image_urls(response_json)[0]
            image_response = client.get(image_url)
            image_response.raise_for_status()
    except (httpx.HTTPError, IndexError, ValueError, KeyError, TypeError) as exc:
        raise AigcProviderError(f"AIGC provider request failed: {exc}") from exc

    mime_type = image_response.headers.get("content-type", "").split(";", 1)[0]
    if mime_type == "image/jpg":
        mime_type = "image/jpeg"
    if not mime_type.startswith("image/"):
        mime_type = _mime_from_url(image_url)
    if mime_type == "image/jpg":
        mime_type = "image/jpeg"
    if not mime_type.startswith("image/"):
        mime_type = "image/png"
    return AigcEditResponse(
        image_base64=base64.b64encode(image_response.content).decode("ascii"),
        mime_type=mime_type,
        prompt_used=prompt,
        provider="dashscope",
        model=model,
    )


def _resolve_source(relative_path: str) -> Path:
    root = Path(settings.storage_root).expanduser().resolve()
    candidate = (root / relative_path).resolve()
    if root not in candidate.parents or not candidate.is_file():
        raise AigcProviderError("Source image is not available in configured storage")
    return candidate


def _image_data_uri(path: Path) -> str:
    try:
        size = path.stat().st_size
        image_bytes = path.read_bytes()
    except OSError as exc:
        raise AigcProviderError("Source image cannot be read") from exc
    if size > 10 * 1024 * 1024:
        raise AigcProviderError("Source image exceeds the provider 10 MB limit")
    mime_type, _ = mimetypes.guess_type(path.name)
    if not mime_type or not mime_type.startswith("image/"):
        raise AigcProviderError("Source image format is not supported")
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{mime_type};base64,{encoded}"


def _extract_image_urls(value: Any) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"image", "url"} and isinstance(child, str):
                if child.startswith(("http://", "https://")):
                    found.append(child)
            else:
                found.extend(_extract_image_urls(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(_extract_image_urls(child))
    return list(dict.fromkeys(found))


def _mime_from_url(url: str) -> str:
    mime_type, _ = mimetypes.guess_type(url.split("?", 1)[0])
    return mime_type or ""
