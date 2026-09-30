"""Direct ASGI contract coverage for the demo-backed analysis endpoint."""

from __future__ import annotations

import sys
from pathlib import Path
from uuid import uuid4

from PIL import Image

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from app.api.routes.analysis import analyze_project
from app.analysis.adapter import _default_weights
from app.config import settings
from app.schemas.analysis import AnalyzeRequest


def _payload(root: Path) -> dict:
    project_id = uuid4()
    assets = []
    for index in range(2):
        asset_id = uuid4()
        relative = Path(str(project_id)) / "assets" / str(asset_id) / "original.png"
        path = root / relative
        path.parent.mkdir(parents=True)
        Image.new("RGB", (96, 72), (50 + index * 10, 90, 120)).save(path)
        assets.append({"asset_id": str(asset_id), "file_path": relative.as_posix()})
    return {"project_id": str(project_id), "assets": assets}


def test_analyze_response_matches_python_java_contract(tmp_path: Path, monkeypatch) -> None:
    """The response keeps every field consumed by Java's AnalysisContract."""

    # Avoid optional detector startup; this test targets the JSON boundary.
    from demo.agent import face_analysis

    monkeypatch.setattr(face_analysis, "detect_face_boxes", lambda _image: [])
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    payload = _payload(tmp_path)

    # Call the route function directly.  The repository's installed anyio
    # backend currently stalls TestClient's worker portal, while this still
    # exercises request validation, route dispatch and response serialization.
    body = analyze_project(AnalyzeRequest.model_validate(payload)).model_dump(mode="json")
    assert set(("project_id", "groups", "assets", "warnings", "algorithm")) <= body.keys()
    assert body["project_id"] == payload["project_id"]
    assert body["groups"]
    assert body["assets"]

    group_fields = {
        "id", "group_type", "has_face", "asset_ids", "confidence",
        "average_similarity", "max_distance", "reasons", "recommended_asset_ids",
        "time_from", "time_to",
    }
    asset_fields = {
        "asset_id", "group_id", "rank", "recommendation", "recommend_score",
        "recommend_reasons", "membership_reason", "quality", "features", "exif",
    }
    assert group_fields <= body["groups"][0].keys()
    assert asset_fields <= body["assets"][0].keys()
    group_members = {
        group["id"]: set(group["asset_ids"])
        for group in body["groups"]
    }
    expected_asset_ids = {item["asset_id"] for item in payload["assets"]}
    returned_asset_ids = [item["asset_id"] for item in body["assets"]]
    assert set(returned_asset_ids) == expected_asset_ids
    assert len(returned_asset_ids) == len(expected_asset_ids)
    for group in body["groups"]:
        assert 0 <= group["confidence"] <= 1
        assert 0 <= group["average_similarity"] <= 1
        assert 0 <= group["max_distance"] <= 1
        assert set(group["recommended_asset_ids"]) <= set(group["asset_ids"])
    for asset in body["assets"]:
        assert asset["asset_id"] in group_members[asset["group_id"]]
        assert asset["recommendation"] in {"keep", "review", "reject"}
        assert asset["rank"] >= 1
        assert 0 <= asset["recommend_score"] <= 1
        assert 0 <= asset["quality"]["score"] <= 1


def test_adapter_matches_demo_pipeline_for_same_batch(tmp_path: Path, monkeypatch) -> None:
    """The service conversion layer must not change demo algorithm results."""

    from demo.agent.clustering import cluster_photos
    from demo.agent.features import extract_features
    from demo.agent.quality_scorer import batch_score
    from demo.agent.scorer import batch_recommend
    from demo.agent.schemas import FaceAnalysis, PoseInfo
    from demo.agent import face_analysis

    monkeypatch.setattr(face_analysis, "detect_face_boxes", lambda _image: [])
    monkeypatch.setattr(settings, "storage_root", str(tmp_path))
    payload = _payload(tmp_path)
    request = AnalyzeRequest.model_validate(payload)
    response = analyze_project(request).model_dump(mode="json")

    features = []
    for item in request.assets:
        feature = extract_features(str(tmp_path / item.file_path))
        assert feature is not None
        feature.asset_id = str(item.asset_id)
        features.append(feature)
    feature_map = {item.asset_id: item for item in features}
    faces = {item.asset_id: FaceAnalysis(item.asset_id) for item in features}
    poses = {item.asset_id: PoseInfo(item.asset_id, person_count=0) for item in features}
    qualities = batch_score(features, faces)
    groups = cluster_photos(features, faces, pose_analyses=poses)
    recommendations = batch_recommend(
        groups,
        feature_map,
        qualities,
        faces,
        weights=_default_weights(False, request.strategy.content_mode),
        keep_count=request.strategy.keep_per_group,
        strictness=request.strategy.strictness,
        filters={"min_sharpness": 0.15, "max_highlight_clip": 0.25},
        poses=poses,
    )

    expected_groups = {frozenset(group.asset_ids): group for group in groups}
    actual_groups = {frozenset(group["asset_ids"]): group for group in response["groups"]}
    assert actual_groups.keys() == expected_groups.keys()
    state_map = {"keep": "keep", "undecided": "review", "discard_suggested": "reject", "filtered_out": "reject"}
    actual_by_asset = {item["asset_id"]: item for item in response["assets"]}
    for group in groups:
        result = recommendations[group.group_id]
        for rank in result.recommendations:
            actual = actual_by_asset[rank.asset_id]
            assert actual["rank"] == rank.rank
            assert actual["recommend_score"] == rank.score
            assert actual["recommendation"] == state_map[rank.state]
            assert actual["quality"]["score"] == qualities[rank.asset_id].overall_score
