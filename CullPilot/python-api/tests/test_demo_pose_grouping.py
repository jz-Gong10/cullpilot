"""Model-free regression tests for the demo pose-aware grouping routes."""

import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import pytest

from demo.agent.clustering import cluster_photos, compute_similarity_breakdown
from demo.agent.llm_provider import parse_instruction
from demo.agent.pose_analysis import body_pose_name, hand_action_name
from demo.agent.schemas import ImageFeatures, PoseInfo


def _feature(asset_id: str, face_count: int) -> ImageFeatures:
    return ImageFeatures(
        asset_id=asset_id,
        file_path=asset_id,
        file_name=f"{asset_id}.jpg",
        has_face=True,
        sharpness=0.5,
        brightness=0.5,
        contrast=0.5,
        highlight_clip=0.0,
        shadow_clip=0.0,
        color_histogram=[0.1],
        phash="0",
        timestamp=1.0,
        face_count=face_count,
    )


def test_demo_pose_labels_and_routes() -> None:
    assert body_pose_name("sitting") == "坐姿"
    assert hand_action_name("peace_sign") == "比耶"

    features = [_feature("sitting", 1), _feature("standing", 1), _feature("pair", 2)]
    poses = {
        "sitting": PoseInfo("sitting", body_pose="sitting", person_count=1),
        "standing": PoseInfo("standing", body_pose="standing", person_count=1),
        "pair": PoseInfo("pair", person_count=2),
    }
    groups = cluster_photos(features, {}, distance_threshold=0.95, pose_analyses=poses)
    by_asset = {asset_id: group for group in groups for asset_id in group.asset_ids}
    assert by_asset["sitting"].body_pose == "sitting"
    assert by_asset["standing"].body_pose == "standing"
    assert by_asset["pair"].group_type == "portrait_people"
    assert by_asset["pair"].participant_count == 2


def test_demo_instruction_parses_pose_filters(monkeypatch: pytest.MonkeyPatch) -> None:
    from demo.agent import llm_provider

    monkeypatch.setattr(llm_provider, "_get_api_key", lambda: "")
    strategy = parse_instruction("只看坐姿且保留比耶的照片")
    assert strategy.body_poses == ["sitting"]
    assert strategy.hand_actions == ["peace_sign"]


def test_chinese_clip_similarity_is_used_when_present() -> None:
    first, second = _feature("first", 1), _feature("second", 1)
    first.clip_image_embedding = [1.0, 0.0]
    second.clip_image_embedding = [1.0, 0.0]

    breakdown = compute_similarity_breakdown(first, second, False)

    assert breakdown["semantic_available"] is True
    assert breakdown["clip_semantic"] == 1.0
    assert breakdown["semantic_models"] == ["OFA-Sys/chinese-clip-vit-base-patch16"]


def test_chinese_clip_enrichment_reports_success(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from PIL import Image
    from demo.agent import chinese_clip_encoder, semantic_cache
    from demo.agent.features import enrich_clip_embeddings

    path = tmp_path / "photo.png"
    Image.new("RGB", (16, 16), "blue").save(path)
    feature = _feature("photo", 0)
    feature.file_path = str(path)
    monkeypatch.setenv("ENABLE_CHINESE_CLIP", "1")
    monkeypatch.setattr(semantic_cache, "load", lambda *_args: None)
    monkeypatch.setattr(semantic_cache, "save", lambda *_args: None)
    monkeypatch.setattr(chinese_clip_encoder, "encode_image", lambda _image: [1.0, 0.0])
    monkeypatch.setattr(chinese_clip_encoder, "status", lambda: {"available": True, "error": None})

    result = enrich_clip_embeddings([feature])

    assert result["enabled"] is True
    assert feature.clip_image_embedding == [1.0, 0.0]
