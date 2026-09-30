"""Pytest coverage for the algorithm pipeline originally shipped in ``demo``.

The demo test was an executable script that relied on a user's photo folder and
optional model/API credentials.  CullPilot runs the same stages as a service,
so this test keeps the useful pipeline contract while using deterministic
temporary images.  Face and semantic models are intentionally not required;
their optional paths are covered by the service/API tests.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

# Add the repository root so the test imports the canonical algorithm package.
REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from demo.agent import face_analysis
from demo.agent.clustering import cluster_photos
from demo.agent.features import batch_extract
from demo.agent.quality_scorer import batch_score
from demo.agent.scorer import batch_recommend
from demo.agent.schemas import FaceAnalysis, PoseInfo, UserWeights


@pytest.fixture
def demo_photo_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Create a small, model-free photo set for the demo pipeline.

    Patching detection is deliberate: this fixture exercises the feature,
    quality, grouping and recommendation contracts without downloading or
    initializing MediaPipe/DeepFace in a unit test.
    """

    monkeypatch.setattr(face_analysis, "detect_face_boxes", lambda _image: [])
    for index, color in enumerate(("#34506b", "#36536e", "#ad7548", "#ae784b")):
        image = Image.new("RGB", (128, 96), color)
        draw = ImageDraw.Draw(image)
        # Two pairs share a visual layout and therefore give the clustering
        # stage something meaningful to compare.
        offset = 4 if index % 2 else 0
        draw.rectangle((20 + offset, 18, 72 + offset, 66), fill="#e4d4b2")
        draw.line((8, 82 - offset, 120, 82 - offset), fill="#f4f0e8", width=2)
        image.save(tmp_path / f"photo-{index}.png")
    return tmp_path


@pytest.fixture
def demo_pipeline(demo_photo_dir: Path) -> dict:
    """Run the same five algorithm stages as ``demo/tests/test_pipeline.py``."""

    features = batch_extract(str(demo_photo_dir))
    assert len(features) == 4
    assert all(feature.phash for feature in features)

    faces = {
        feature.asset_id: FaceAnalysis(asset_id=feature.asset_id)
        for feature in features
    }
    poses = {
        feature.asset_id: PoseInfo(asset_id=feature.asset_id, person_count=0)
        for feature in features
    }
    qualities = batch_score(features, faces)
    groups = cluster_photos(features, faces, pose_analyses=poses)
    recommendations = batch_recommend(
        groups,
        {feature.asset_id: feature for feature in features},
        qualities,
        faces,
        weights=UserWeights(
            sharpness=0.30,
            eyes_open=0.25,
            expression=0.20,
            exposure=0.15,
            composition=0.10,
        ),
    )
    return {
        "features": features,
        "faces": faces,
        "qualities": qualities,
        "groups": groups,
        "recommendations": recommendations,
    }


def test_demo_feature_and_quality_stages(demo_pipeline: dict) -> None:
    """Feature extraction and quality scores retain the demo value ranges."""

    features = demo_pipeline["features"]
    qualities = demo_pipeline["qualities"]
    assert {feature.asset_id for feature in features} == set(qualities)
    for feature in features:
        assert 0 <= feature.sharpness <= 1
        assert 0 <= feature.brightness <= 1
        assert 0 <= feature.contrast <= 1
        assert 0 <= feature.highlight_clip <= 1
        assert 0 <= feature.shadow_clip <= 1
        assert feature.face_count == len(feature.face_boxes)
    for quality in qualities.values():
        assert 0 <= quality.overall_score <= 1
        assert 0 <= quality.confidence <= 1


def test_demo_grouping_and_recommendation_cover_every_asset(demo_pipeline: dict) -> None:
    """Grouping and MMR recommendation do not lose or duplicate assets."""

    features = demo_pipeline["features"]
    groups = demo_pipeline["groups"]
    recommendations = demo_pipeline["recommendations"]
    asset_ids = {feature.asset_id for feature in features}
    grouped_ids = [asset_id for group in groups for asset_id in group.asset_ids]

    assert groups
    assert set(grouped_ids) == asset_ids
    assert len(grouped_ids) == len(set(grouped_ids))
    assert set(recommendations) == {group.group_id for group in groups}
    for group in groups:
        result = recommendations[group.group_id]
        assert result.recommendations
        assert [item.rank for item in result.recommendations] == list(
            range(1, len(result.recommendations) + 1)
        )
        assert all(0 <= item.score <= 1 for item in result.recommendations)
        assert all(item.state in {"keep", "review", "undecided", "discard_suggested", "filtered_out"}
                   for item in result.recommendations)


def test_demo_pipeline_is_deterministic(demo_photo_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The same input batch produces comparable groups, ranks and quality scores."""

    monkeypatch.setattr(face_analysis, "detect_face_boxes", lambda _image: [])

    def run_once() -> tuple:
        features = batch_extract(str(demo_photo_dir))
        faces = {feature.asset_id: FaceAnalysis(feature.asset_id) for feature in features}
        poses = {feature.asset_id: PoseInfo(feature.asset_id, person_count=0) for feature in features}
        qualities = batch_score(features, faces)
        groups = cluster_photos(features, faces, pose_analyses=poses)
        recommendations = batch_recommend(
            groups,
            {feature.asset_id: feature for feature in features},
            qualities,
            faces,
            weights=UserWeights(sharpness=1.0),
        )
        group_summary = tuple(
            (tuple(group.asset_ids), group.group_type, group.has_face)
            for group in groups
        )
        quality_summary = tuple(
            (asset_id, quality.overall_score)
            for asset_id, quality in sorted(qualities.items())
        )
        recommendation_summary = tuple(
            (
                group_id,
                tuple((item.asset_id, item.rank, item.state, item.score)
                      for item in result.recommendations),
            )
            for group_id, result in sorted(recommendations.items())
        )
        return group_summary, quality_summary, recommendation_summary

    assert run_once() == run_once()
