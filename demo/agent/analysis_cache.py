"""Simple JSON persistence for the CPU demo analysis graph."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict
from pathlib import Path
from typing import Optional

from .schemas import (
    FaceAnalysis,
    FaceBox,
    FaceInfo,
    GroupRecommendation,
    ImageFeatures,
    PhotoGroup,
    PhotoRank,
    PoseInfo,
    ProjectData,
    QualityScore,
)

CACHE_ROOT = Path(__file__).resolve().parents[1] / "data" / ".analysis_cache"
# Bump when cache metadata or serialized data semantics change.
SCHEMA_VERSION = 4


def _jsonable(value):
    if isinstance(value, tuple):
        return [_jsonable(item) for item in value]
    if isinstance(value, list):
        return [_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    return value


def inventory(directory: str, extensions: set[str]) -> list[dict]:
    root = Path(directory).resolve()
    rows = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in extensions:
            continue
        stat = path.stat()
        rows.append({
            "path": path.relative_to(root).as_posix(),
            "size": stat.st_size,
            "mtime_ns": stat.st_mtime_ns,
        })
    return rows


def cache_file(directory: str) -> Path:
    key = hashlib.sha1(str(Path(directory).resolve()).encode("utf-8")).hexdigest()[:16]
    path = CACHE_ROOT / key
    path.mkdir(parents=True, exist_ok=True)
    return path / "analysis.json"


def _feature_from_dict(data: dict) -> ImageFeatures:
    data = dict(data)
    data["face_boxes"] = [FaceBox(tuple(item["bbox"]), item.get("confidence", 0.0)) if isinstance(item, dict) else FaceBox(tuple(item), 0.0) for item in data.get("face_boxes", [])]
    return ImageFeatures(**data)


def _face_from_dict(data: dict) -> FaceInfo:
    data = dict(data)
    for key in ("bbox", "center"):
        if key in data and isinstance(data[key], list):
            data[key] = tuple(data[key])
    return FaceInfo(**data)


def _analysis_from_dict(data: dict) -> FaceAnalysis:
    data = dict(data)
    data["faces"] = [_face_from_dict(item) for item in data.get("faces", [])]
    return FaceAnalysis(**data)


def _group_from_dict(data: dict) -> PhotoGroup:
    data = dict(data)
    if isinstance(data.get("time_range"), list):
        data["time_range"] = tuple(data["time_range"])
    return PhotoGroup(**data)


def _pose_from_dict(data: dict) -> PoseInfo:
    return PoseInfo(**dict(data))


def _quality_from_dict(data: dict) -> QualityScore:
    return QualityScore(**data)


def _recommendation_from_dict(data: dict) -> GroupRecommendation:
    data = dict(data)
    data["recommendations"] = [PhotoRank(**item) for item in data.get("recommendations", [])]
    return GroupRecommendation(**data)


def load(directory: str, current_inventory: list[dict], visual_model: str, clip_model: str,
         cluster_threshold: float | None = None, dino_enabled: bool = False,
         clip_enabled: bool = False, semantic_device: str = "cpu") -> Optional[dict]:
    path = cache_file(directory)
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema_version") != SCHEMA_VERSION:
            return None
        if payload.get("inventory") != current_inventory:
            return None
        if payload.get("visual_model") != visual_model or payload.get("clip_model") != clip_model:
            return None
        if bool(payload.get("dino_enabled", False)) != bool(dino_enabled):
            return None
        if bool(payload.get("clip_enabled", False)) != bool(clip_enabled):
            return None
        if payload.get("semantic_device", "cpu") != semantic_device:
            return None
        if cluster_threshold is not None and abs(float(payload.get("cluster_threshold", cluster_threshold)) - float(cluster_threshold)) > 1e-9:
            return None
        data = payload["data"]
        return {
            "assets": {key: _feature_from_dict(value) for key, value in data.get("assets", {}).items()},
            "qualities": {key: _quality_from_dict(value) for key, value in data.get("qualities", {}).items()},
            "faces": {key: _analysis_from_dict(value) for key, value in data.get("faces", {}).items()},
            "poses": {key: _pose_from_dict(value) for key, value in data.get("poses", {}).items()},
            "groups": {key: _group_from_dict(value) for key, value in data.get("groups", {}).items()},
            "recommendations": {key: _recommendation_from_dict(value) for key, value in data.get("recommendations", {}).items()},
            "user_decisions": data.get("user_decisions", {}),
        }
    except Exception:
        return None


def save(directory: str, current_inventory: list[dict], visual_model: str, clip_model: str,
         data: ProjectData, cluster_threshold: float | None = None,
         dino_enabled: bool = False, clip_enabled: bool = False,
         semantic_device: str = "cpu") -> None:
    path = cache_file(directory)
    payload = {
        "schema_version": SCHEMA_VERSION,
        "inventory": current_inventory,
        "visual_model": visual_model,
        "clip_model": clip_model,
        "dino_enabled": bool(dino_enabled),
        "clip_enabled": bool(clip_enabled),
        "semantic_device": semantic_device,
        "cluster_threshold": cluster_threshold,
        "data": _jsonable(asdict(data)),
    }
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    temp.replace(path)
