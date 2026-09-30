from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from app.api.routes.analysis import analyze_project
from app.config import settings
from app.main import app
from app.schemas.analysis import AnalyzeRequest
from app.services.instruction_parser import parse_instruction


def _payload(root: Path, count: int = 2) -> dict:
    project_id = uuid4()
    assets = []
    for index in range(count):
        asset_id = uuid4()
        relative = Path(str(project_id)) / "assets" / str(asset_id) / "original.png"
        path = root / relative
        path.parent.mkdir(parents=True)
        image = Image.new("RGB", (96, 72), "#447755")
        draw = ImageDraw.Draw(image)
        draw.rectangle((18 + index, 14, 58 + index, 52), fill="#ddeccd")
        image.save(path)
        assets.append({"asset_id": str(asset_id), "file_path": relative.as_posix()})
    return {"project_id": str(project_id), "assets": assets}


def test_analyze_groups_and_ranks_images(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    response = analyze_project(AnalyzeRequest.model_validate(_payload(tmp_path)))
    data = response.model_dump(mode="json")
    assert len(data["assets"]) == 2
    assert len(data["groups"]) == 1
    assert sorted(item["rank"] for item in data["assets"]) == [1, 2]
    assert {item["asset_id"] for item in data["assets"]} == {
        item for group in data["groups"] for item in group["asset_ids"]
    }
    assert all(item["recommendation"] in {"keep", "review", "reject"} for item in data["assets"])
    assert all(0 <= item["quality"]["score"] <= 1 for item in data["assets"])


def test_analyze_rejects_foreign_or_mismatched_path(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    payload = _payload(tmp_path, 1)
    payload["assets"][0]["file_path"] = "../demo/data/sample_photos/a.jpg"
    try:
        analyze_project(AnalyzeRequest.model_validate(payload))
    except HTTPException as exc:
        assert exc.status_code == 422
    else:
        raise AssertionError("foreign storage path was accepted")


def test_rebuild_false_preserves_group_id(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    payload = _payload(tmp_path, 1)
    group_id = uuid4()
    payload["rebuild_groups"] = False
    payload["existing_groups"] = [{"id": str(group_id), "asset_ids": [payload["assets"][0]["asset_id"]]}]
    response = analyze_project(AnalyzeRequest.model_validate(payload))
    assert response.groups[0].id == group_id


def test_empty_instruction_uses_content_aware_defaults() -> None:
    response = parse_instruction("")
    assert response.fallback_used is True
    assert response.strategy.keep_per_group == 2
    assert response.strategy.content_mode == "auto"
    assert response.strategy.weights == {}


def test_instruction_maps_supported_quality_terms() -> None:
    response = parse_instruction("每组保留3张最清晰、人物睁眼、正脸的照片")
    assert response.fallback_used is False
    assert response.strategy.keep_per_group == 3
    assert response.strategy.weights["sharpness"] > response.strategy.weights["exposure"]
    assert response.strategy.weights["eyesOpen"] > response.strategy.weights["expression"]
    assert response.strategy.constraints["preferFrontFacing"] is True


def test_model_health_reports_runtime_compatibility() -> None:
    response = TestClient(app).get("/api/v1/health/models")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] in {"ok", "degraded"}
    assert set(data["components"]) == {"mediapipe", "deepface", "dinov2", "chinese_clip"}
    assert data["components"]["chinese_clip"]["enabled"] is False
    assert data["components"]["chinese_clip"]["weights_present"] is (
        data["inventory"]["chinese_clip"]["present"] == data["inventory"]["chinese_clip"]["total"]
    )
    assert isinstance(data["components"]["mediapipe"]["legacy_solutions_api"], bool)
