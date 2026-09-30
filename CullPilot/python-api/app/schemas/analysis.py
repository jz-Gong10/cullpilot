from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class AnalyzeAsset(BaseModel):
    asset_id: UUID
    file_path: str = Field(min_length=1)


class ExistingGroup(BaseModel):
    id: UUID
    asset_ids: list[UUID]


class AnalyzeStrategy(BaseModel):
    keep_per_group: int = Field(default=2, ge=1, le=10)
    strictness: Literal["loose", "standard", "strict"] = "standard"
    content_mode: Literal["auto", "portrait", "landscape", "mixed"] = "auto"
    weights: dict[str, float] = Field(default_factory=dict)
    constraints: dict[str, bool] = Field(default_factory=dict)


class AnalyzeRequest(BaseModel):
    project_id: UUID
    assets: list[AnalyzeAsset] = Field(min_length=1, max_length=1000)
    strategy: AnalyzeStrategy = Field(default_factory=AnalyzeStrategy)
    rebuild_groups: bool = True
    existing_groups: list[ExistingGroup] = Field(default_factory=list)


class GroupResult(BaseModel):
    id: UUID
    group_type: str
    has_face: bool
    asset_ids: list[UUID]
    confidence: float
    average_similarity: float
    max_distance: float
    reasons: list[str]
    recommended_asset_ids: list[UUID]
    time_from: str | None = None
    time_to: str | None = None


class AssetResult(BaseModel):
    asset_id: UUID
    group_id: UUID
    rank: int
    recommendation: Literal["keep", "review", "reject"]
    recommend_score: float
    recommend_reasons: list[str]
    membership_reason: str
    quality: dict
    features: dict
    exif: dict


class AnalyzeResponse(BaseModel):
    project_id: UUID
    groups: list[GroupResult]
    assets: list[AssetResult]
    warnings: list[str] = Field(default_factory=list)
    algorithm: dict[str, str] = Field(default_factory=lambda: {"name": "demo-photo-selection", "version": "1.0"})
