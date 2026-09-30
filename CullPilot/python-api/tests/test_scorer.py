import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from demo.agent.scorer import batch_recommend
from demo.agent.schemas import ImageFeatures, PhotoGroup, QualityScore, UserWeights


def _feature(asset_id: str) -> ImageFeatures:
    return ImageFeatures(
        asset_id=asset_id,
        file_path=f"{asset_id}.jpg",
        file_name=f"{asset_id}.jpg",
        has_face=False,
        sharpness=0.5,
        brightness=0.5,
        contrast=0.5,
        highlight_clip=0.0,
        shadow_clip=0.0,
        color_histogram=[1.0],
        phash="0",
    )


def test_keep_per_group_is_applied_independently() -> None:
    groups = [
        PhotoGroup("group-a", False, ["a1", "a2", "a3"], 1.0),
        PhotoGroup("group-b", False, ["b1", "b2", "b3"], 1.0),
    ]
    features = {asset_id: _feature(asset_id) for group in groups for asset_id in group.asset_ids}
    scores = {
        "a1": 0.80, "a2": 0.70, "a3": 0.10,
        "b1": 0.80, "b2": 0.70, "b3": 0.05,
    }
    qualities = {
        asset_id: QualityScore(
            asset_id=asset_id,
            overall_score=score,
            confidence=1.0,
            sharpness_score=score,
            exposure_score=score,
            contrast_score=score,
            shadow_score=score,
            highlight_score=score,
            composition_score=score,
        )
        for asset_id, score in scores.items()
    }

    result = batch_recommend(
        groups,
        features,
        qualities,
        faces={},
        weights=UserWeights(sharpness=1.0),
        keep_count=2,
        strictness="strict",
    )

    assert [item.asset_id for item in result["group-a"].recommendations if item.state == "keep"] == ["a1", "a2"]
    assert [item.asset_id for item in result["group-b"].recommendations if item.state == "keep"] == ["b1", "b2"]
