"""Shared data structures for the algorithm demo."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class FaceBox:
    bbox: tuple[int, int, int, int] = (0, 0, 0, 0)
    confidence: float = 0.0

    @property
    def center(self) -> tuple[float, float]:
        x, y, w, h = self.bbox
        return (x + w / 2.0, y + h / 2.0)


@dataclass
class ImageFeatures:
    """One image's raw, measurable features."""

    asset_id: str
    file_path: str
    file_name: str
    has_face: bool
    sharpness: float
    brightness: float
    contrast: float
    highlight_clip: float
    shadow_clip: float
    color_histogram: list
    phash: str
    timestamp: Optional[float] = None
    width: int = 0
    height: int = 0
    file_size: int = 0
    exif_datetime: Optional[str] = None
    face_count: int = 0
    face_boxes: list = field(default_factory=list)
    texture_score: float = 0.0
    visual_embedding: Optional[list] = None
    clip_image_embedding: Optional[list] = None
    semantic_status: str = "pending"


@dataclass
class FaceInfo:
    """Attributes for one detected face."""

    face_id: int
    bbox: tuple = (0, 0, 0, 0)
    center: tuple = (0.0, 0.0)
    face_size_ratio: float = 0.0
    detection_confidence: float = 0.0
    age: Optional[float] = None
    gender: Optional[str] = None
    emotion: Optional[str] = None
    eye_aspect_ratio: Optional[float] = None
    eyes_open: Optional[bool] = None
    head_yaw: Optional[float] = None
    head_pitch: Optional[float] = None
    embedding: Optional[list] = None
    face_completeness: float = 1.0
    is_frontal: bool = True
    is_smiling: bool = False
    analysis_status: str = "ok"
    analysis_error: Optional[str] = None


@dataclass
class FaceAnalysis:
    asset_id: str
    face_count: int = 0
    faces: list = field(default_factory=list)
    detector_status: str = "ok"
    detector_error: Optional[str] = None


@dataclass
class PoseInfo:
    """Interpretable single-person pose and hand-action result."""

    asset_id: str
    body_pose: str = "no_specific_pose"
    hand_action: str = "no_specific_hand_action"
    pose_confidence: float = 0.0
    visible_ratio: float = 0.0
    person_count: int = 0
    keypoints: list = field(default_factory=list)
    hand_keypoints: list = field(default_factory=list)
    analysis_status: str = "ok"
    analysis_error: Optional[str] = None

    @property
    def pose_label(self) -> str:
        """Backward-compatible primary label used by older callers."""
        return self.body_pose

    @property
    def group_key(self) -> str:
        if self.person_count != 1:
            return f"people_{self.person_count}" if self.person_count < 4 else "people_4_plus"
        return self.body_pose


@dataclass
class QualityScore:
    asset_id: str
    overall_score: float
    confidence: float
    mode: str = "landscape"
    sharpness_score: float = 0.0
    exposure_score: float = 0.0
    contrast_score: float = 0.0
    shadow_score: float = 0.0
    highlight_score: float = 0.0
    eyes_open_score: float = 0.0
    expression_score: float = 0.0
    composition_score: float = 0.0
    completeness_score: float = 0.0
    frontal_score: float = 0.0
    face_score: float = 0.0
    reasons: list = field(default_factory=list)
    warnings: list = field(default_factory=list)


@dataclass
class PhotoGroup:
    group_id: str
    has_face: bool
    asset_ids: list
    confidence: float
    reasons: list = field(default_factory=list)
    time_range: tuple = (0.0, 0.0)
    group_type: str = "scene"
    average_similarity: float = 0.0
    max_distance: float = 0.0
    participant_count: Optional[int] = None
    semantic_available: bool = False
    semantic_model: Optional[str] = None
    semantic_similarity: float = 0.0
    similarity_breakdown: dict = field(default_factory=dict)
    body_pose: Optional[str] = None
    hand_action: Optional[str] = None


@dataclass
class UserWeights:
    sharpness: float = 0.25
    eyes_open: float = 0.25
    expression: float = 0.20
    exposure: float = 0.15
    composition: float = 0.15
    contrast: float = 0.0
    shadow: float = 0.0
    highlight: float = 0.0
    face_completeness: float = 0.0
    frontal: float = 0.0

    def normalized(self) -> "UserWeights":
        values = {key: max(0.0, float(getattr(self, key))) for key in self.__dataclass_fields__}
        total = sum(values.values())
        if total <= 1e-9:
            return UserWeights()
        return UserWeights(**{key: value / total for key, value in values.items()})


@dataclass
class PhotoRank:
    asset_id: str
    rank: int
    score: float
    state: str
    reasons: list = field(default_factory=list)
    filtered_out: bool = False
    filter_reasons: list = field(default_factory=list)


@dataclass
class GroupRecommendation:
    group_id: str
    keep_count: int = 2
    strictness: str = "standard"
    recommendations: list = field(default_factory=list)


@dataclass
class ParsedStrategy:
    keep_per_group: Optional[int] = None
    strictness: Optional[str] = None
    weights: Optional[UserWeights] = None
    min_quality: Optional[float] = None
    min_sharpness: Optional[float] = None
    max_highlight_clip: Optional[float] = None
    max_shadow_clip: Optional[float] = None
    require_face: Optional[bool] = None
    require_eyes_open: Optional[bool] = None
    require_frontal: Optional[bool] = None
    require_smile: Optional[bool] = None
    min_face_completeness: Optional[float] = None
    body_poses: Optional[list] = None
    hand_actions: Optional[list] = None
    constraints: dict = field(default_factory=dict)
    unsupported_terms: list = field(default_factory=list)
    explanation: str = ""


@dataclass
class PhotoRecord:
    """Unified view used by the service and test console."""

    features: ImageFeatures
    faces: FaceAnalysis
    quality: QualityScore
    group_id: Optional[str] = None
    recommendation: Optional[PhotoRank] = None


@dataclass
class ProjectData:
    assets: dict = field(default_factory=dict)
    qualities: dict = field(default_factory=dict)
    faces: dict = field(default_factory=dict)
    poses: dict = field(default_factory=dict)
    groups: dict = field(default_factory=dict)
    recommendations: dict = field(default_factory=dict)
    user_decisions: dict = field(default_factory=dict)
