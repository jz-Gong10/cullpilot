"""Fast tests for the pose-aware grouping contract (no model inference)."""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from agent.clustering import cluster_photos
from agent.pose_analysis import body_pose_name, hand_action_name
from agent.llm_provider import parse_instruction
from agent.schemas import ImageFeatures, PoseInfo


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


def test_pose_labels_are_user_facing():
    assert body_pose_name("sitting") == "坐姿"
    assert body_pose_name("unknown") == "无特定姿态"
    assert hand_action_name("peace_sign") == "比耶"


def test_single_person_pose_and_multi_person_count_are_separate_routes():
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


def test_local_instruction_parses_pose_filters():
    strategy = parse_instruction("只看坐姿且保留比耶的照片")
    assert strategy.body_poses == ["sitting"]
    assert strategy.hand_actions == ["peace_sign"]
