import base64
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.services import aigc_service


def _source(root: Path) -> str:
    relative = Path("project") / "assets" / "asset" / "original.png"
    path = root / relative
    path.parent.mkdir(parents=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\nsource")
    return relative.as_posix()


def test_empty_prompt_uses_default_and_returns_generated_image(tmp_path: Path, monkeypatch) -> None:
    client = TestClient(app)
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    monkeypatch.setattr(settings, "aigc_api_key", "test-key")
    monkeypatch.setattr(settings, "aigc_model", "test-configured-model")
    source_path = _source(tmp_path)
    generated = b"\x89PNG\r\n\x1a\ngenerated"
    requests = []

    class ProviderClient:
        def __init__(self, timeout):
            assert timeout == settings.aigc_timeout_seconds

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def post(self, url, headers, json):
            requests.append(json)
            assert headers["Authorization"] == "Bearer test-key"
            return httpx.Response(
                200,
                json={"output": {"choices": [{"message": {"content": [
                    {"image": "https://example.com/generated.png"}
                ]}}]}},
                request=httpx.Request("POST", url),
            )

        def get(self, url):
            return httpx.Response(
                200, content=generated, headers={"content-type": "image/png"},
                request=httpx.Request("GET", url),
            )

    monkeypatch.setattr(aigc_service.httpx, "Client", ProviderClient)
    response = client.post("/api/v1/internal/aigc/image-edit", json={"source_path": source_path})
    assert response.status_code == 200, response.text
    assert response.json()["prompt_used"] == aigc_service.DEFAULT_BEAUTIFY_PROMPT
    assert response.json()["mime_type"] == "image/png"
    assert base64.b64decode(response.json()["image_base64"]) == generated
    assert requests[0]["input"]["messages"][0]["content"][1]["text"] == aigc_service.DEFAULT_BEAUTIFY_PROMPT
    assert requests[0]["model"] == settings.aigc_model


def test_source_outside_storage_is_rejected(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "storage_root", str(tmp_path / "storage"))
    outside = tmp_path / "secret.png"
    outside.write_bytes(b"secret")
    response = TestClient(app).post(
        "/api/v1/internal/aigc/image-edit", json={"source_path": "../secret.png"}
    )
    assert response.status_code == 502
    assert "not available" in response.json()["detail"]


def test_provider_error_returns_502(tmp_path: Path, monkeypatch) -> None:
    client = TestClient(app)
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    monkeypatch.setattr(settings, "aigc_api_key", "test-key")
    source_path = _source(tmp_path)

    class FailingProvider:
        def __init__(self, timeout):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def post(self, url, headers, json):
            return httpx.Response(503, request=httpx.Request("POST", url))

    monkeypatch.setattr(aigc_service.httpx, "Client", FailingProvider)
    response = client.post("/api/v1/internal/aigc/image-edit", json={"source_path": source_path})
    assert response.status_code == 502
    assert "provider request failed" in response.json()["detail"]
