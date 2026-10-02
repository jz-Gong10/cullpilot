"""Translate CullPilot's internal request contract to the demo algorithms."""

from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean
from uuid import UUID, uuid5

import os
import sys

from app.config import settings
from app.schemas.analysis import AnalyzeRequest, AnalyzeResponse, AssetResult, GroupResult

# The demo directory is the canonical algorithm source.  Keep the import
# boundary here so the API layer can be deployed without duplicating it.
_REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
if str(_REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPOSITORY_ROOT))

# Configure environment-backed demo modules before importing them; model path
# constants are evaluated at import time.
if settings.analysis_model_root:
    os.environ["PHOTO_SCREENER_MODEL_ROOT"] = settings.analysis_model_root
os.environ.setdefault("PHOTO_SCREENER_OFFLINE", "1")
os.environ.setdefault("DEEPFACE_BACKEND_ENGINE", "pytorch")
os.environ["ENABLE_DINO"] = "1" if settings.analysis_enable_semantic else "0"
os.environ["ENABLE_CHINESE_CLIP"] = "1" if settings.analysis_enable_chinese_clip else "0"
os.environ["ANALYSIS_ENABLE_DEEPFACE"] = "1" if settings.analysis_enable_deepface else "0"
_CONFIGURED_DATA_ROOT = os.getenv("PHOTO_SCREENER_DATA_ROOT", "").strip()

from demo.agent import face_analysis
from demo.agent.clustering import cluster_photos, compute_similarity
from demo.agent.features import enrich_visual_embeddings, extract_features
from demo.agent.face_analysis import analyze_faces
from demo.agent.pose_analysis import analyze_pose
from demo.agent.quality_scorer import batch_score
from demo.agent.schemas import FaceAnalysis, PhotoGroup, PoseInfo, UserWeights
from demo.agent.scorer import batch_recommend, recommend_group

if not settings.analysis_enable_deepface:
    # A prior import by another caller must not silently enable DeepFace for
    # this service request when CullPilot has disabled the optional model.
    face_analysis.DeepFace = None
    face_analysis._DEEPFACE_AVAILABLE = False


def _source_path(root: Path, project_id: UUID, asset_id: UUID, relative: str) -> Path:
    parts = Path(relative.replace("\\", "/"))
    expected = root / str(project_id) / "assets" / str(asset_id)
    resolved = (root / parts).resolve()
    if (resolved.parent != expected.resolve() or not resolved.name.startswith("original.")
            or resolved.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}):
        raise ValueError("Invalid asset storage path")
    if not resolved.is_file():
        raise ValueError("Image file is missing")
    return resolved


def _timestamp(value: float | None) -> str | None:
    return datetime.fromtimestamp(value, timezone.utc).isoformat() if value is not None else None


def _readable(items: list[str], fallback: str) -> list[str]:
    readable = [item for item in items if "\ufffd" not in item]
    return readable or [fallback]


def _default_weights(has_face: bool, content_mode: str = "auto") -> UserWeights:
    """Use content-aware defaults when the user did not customize weights."""
    use_portrait = content_mode == "portrait" or (content_mode in {"auto", "mixed"} and has_face)
    if use_portrait:
        return UserWeights(
            sharpness=0.30,
            eyes_open=0.25,
            expression=0.15,
            exposure=0.15,
            composition=0.10,
            frontal=0.05,
        )
    return UserWeights(
        sharpness=0.35,
        exposure=0.20,
        composition=0.20,
        contrast=0.10,
        shadow=0.075,
        highlight=0.075,
    )


def _groups(request: AnalyzeRequest, features: list, faces: dict, poses: dict) -> list[PhotoGroup]:
    if request.rebuild_groups or not request.existing_groups:
        return cluster_photos(features, faces, pose_analyses=poses)
    by_id = {feature.asset_id: feature for feature in features}
    assigned = set()
    preserved = []
    for item in request.existing_groups:
        members = [by_id[str(asset_id)] for asset_id in item.asset_ids if str(asset_id) in by_id]
        if not members:
            continue
        assigned.update(member.asset_id for member in members)
        times = [member.timestamp or 0 for member in members]
        similarity = [
            compute_similarity(left, right, bool(left.face_count), faces)
            for index, left in enumerate(members) for right in members[index + 1:]
        ]
        average = mean(similarity) if similarity else 1.0
        preserved.append(PhotoGroup(
            group_id=str(item.id),
            has_face=bool(members[0].face_count),
            asset_ids=[member.asset_id for member in members],
            confidence=round(average, 3),
            reasons=["Preserved existing group"],
            time_range=(min(times), max(times)),
            group_type="pose" if members[0].face_count else "scene",
            average_similarity=round(average, 3),
            max_distance=round(max((1.0 - item for item in similarity), default=0.0), 3),
        ))
    return preserved + cluster_photos([item for item in features if item.asset_id not in assigned], faces, pose_analyses=poses)


def analyze(request: AnalyzeRequest, storage_root: Path | None = None) -> AnalyzeResponse:
    root = (storage_root or Path(settings.storage_root)).resolve()
    # Keep semantic vectors alongside the active application data directory.
    # Tests and embedded callers can change storage_root between requests;
    # an automatically selected previous path must not become stale.
    if not _CONFIGURED_DATA_ROOT:
        os.environ["PHOTO_SCREENER_DATA_ROOT"] = str(root.parent / "data")
    ids = [str(item.asset_id) for item in request.assets]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate asset IDs")
    paths = {
        str(item.asset_id): _source_path(root, request.project_id, item.asset_id, item.file_path)
        for item in request.assets
    }

    features = []
    for asset_id, path in paths.items():
        feature = extract_features(str(path))
        if feature is None:
            raise ValueError("An image could not be decoded")
        feature.asset_id = asset_id
        features.append(feature)

    warnings = []
    if face_analysis.mp is None:
        warnings.append(
            getattr(face_analysis, "_MEDIAPIPE_ERROR", None)
            or "MediaPipe is unavailable; face and pose detection were skipped"
        )
    if request.strategy.content_mode not in {"auto", "mixed"}:
        warnings.append("Demo scoring uses detected faces per image; content_mode does not force a classification")
    if settings.analysis_enable_semantic:
        semantic = enrich_visual_embeddings(features)
        if not semantic.get("enabled"):
            warnings.append("DINOv2 is unavailable; similarity uses image features only")
    if settings.analysis_enable_chinese_clip:
        from demo.agent.features import enrich_clip_embeddings

        clip_status = enrich_clip_embeddings(features)
        if not clip_status.get("enabled"):
            warnings.append("Chinese-CLIP is unavailable; similarity uses the remaining image features")

    faces = {}
    poses = {}
    for feature in features:
        asset_id = feature.asset_id
        faces[asset_id] = analyze_faces(str(paths[asset_id]), feature.face_boxes) if feature.face_count else FaceAnalysis(asset_id)
        faces[asset_id].asset_id = asset_id
        poses[asset_id] = analyze_pose(str(paths[asset_id]), faces[asset_id]) if feature.face_count == 1 else PoseInfo(asset_id, person_count=feature.face_count)
        poses[asset_id].asset_id = asset_id

    if any(item.face_count for item in features) and not face_analysis._DEEPFACE_AVAILABLE:
        warnings.append("DeepFace is unavailable; portrait groups use visual similarity without identity verification")

    qualities = batch_score(features, faces)
    groups = _groups(request, features, faces, poses)
    weights_data = request.strategy.weights
    constraints = request.strategy.constraints
    weights = UserWeights(
        sharpness=weights_data.get("sharpness", 0.3) + weights_data.get("motion", 0.05),
        eyes_open=weights_data.get("eyesOpen", 0.25),
        expression=weights_data.get("expression", 0.15),
        exposure=weights_data.get("exposure", 0.15),
        composition=weights_data.get("composition", 0.1),
        frontal=0.1 if constraints.get("preferFrontFacing", False) else 0.0,
    )
    filters = {}
    if constraints.get("avoidSevereBlur", True):
        filters["min_sharpness"] = (
            0.15 if constraints.get("allowMildMotionBlur", True) else 0.30
        )
        filters["blur_quality_floor"] = 0.60
    if constraints.get("avoidSevereOverexposure", True):
        filters["max_highlight_clip"] = 0.25
    features_by_id = {item.asset_id: item for item in features}
    if not weights_data:
        # The default is selected per group, so mixed projects can contain
        # portraits and landscapes without forcing one scoring profile on all
        # images.
        recommendations = {
            group.group_id: recommend_group(
                group, features_by_id, qualities, faces,
                weights=_default_weights(group.has_face, request.strategy.content_mode),
                keep_count=request.strategy.keep_per_group,
                strictness=request.strategy.strictness,
                filters=filters,
                poses=poses,
            )
            for group in groups
        }
    else:
        recommendations = batch_recommend(
            groups, features_by_id, qualities, faces,
            weights=weights, keep_count=request.strategy.keep_per_group,
            strictness=request.strategy.strictness, filters=filters, poses=poses,
        )

    existing_ids = {str(item.id) for item in request.existing_groups}
    group_results = []
    asset_results = []
    for group in groups:
        group_id = UUID(group.group_id) if group.group_id in existing_ids else uuid5(request.project_id, ":".join(sorted(group.asset_ids)))
        ranks = recommendations[group.group_id].recommendations
        group_results.append(GroupResult(
            id=group_id,
            group_type=group.group_type,
            has_face=group.has_face,
            asset_ids=[UUID(item) for item in group.asset_ids],
            confidence=group.confidence,
            average_similarity=group.average_similarity,
            max_distance=group.max_distance,
            reasons=_readable(group.reasons, "Similar portraits" if group.has_face else "Similar scene and colors"),
            recommended_asset_ids=[UUID(item.asset_id) for item in ranks if item.state == "keep"],
            time_from=_timestamp(group.time_range[0]),
            time_to=_timestamp(group.time_range[1]),
        ))
        for rank in ranks:
            feature = features_by_id[rank.asset_id]
            quality = qualities[rank.asset_id]
            asset_results.append(AssetResult(
                asset_id=UUID(rank.asset_id), group_id=group_id, rank=rank.rank,
                recommendation={"keep": "keep", "undecided": "review", "discard_suggested": "reject", "filtered_out": "reject"}[rank.state],
                recommend_score=rank.score,
                recommend_reasons=_readable(rank.reasons + rank.filter_reasons,
                                             "Does not meet the quality constraints" if rank.filtered_out else
                                             "Selected by quality and diversity" if rank.state == "keep" else
                                             "Review quality and similarity"),
                membership_reason="; ".join(_readable(group.reasons, "Similar portraits" if group.has_face else "Similar scene and colors")[:2]),
                quality={
                    "score": quality.overall_score,
                    "confidence": quality.confidence,
                    "mode": quality.mode,
                    "reasons": _readable(quality.reasons, "Image quality evaluated"),
                    "warnings": [item for item in quality.warnings if "\ufffd" not in item] + warnings,
                    "dimensions": {
                        key: value for key, value in asdict(quality).items()
                        if key.endswith("_score") and key != "overall_score"
                    },
                },
                features={
                    "hasFace": feature.has_face, "faceCount": feature.face_count,
                    "sharpness": feature.sharpness, "brightness": feature.brightness,
                    "contrast": feature.contrast, "highlightClip": feature.highlight_clip,
                    "shadowClip": feature.shadow_clip, "textureScore": feature.texture_score,
                    "phash": feature.phash, "bodyPose": poses[rank.asset_id].body_pose,
                    "handAction": poses[rank.asset_id].hand_action,
                    "semanticStatus": feature.semantic_status,
                },
                exif={"dateTimeOriginal": feature.exif_datetime} if feature.exif_datetime else {},
            ))
    return AnalyzeResponse(project_id=request.project_id, groups=group_results, assets=asset_results, warnings=warnings)
