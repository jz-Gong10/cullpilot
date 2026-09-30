from pydantic import BaseModel, Field


class ParseInstructionRequest(BaseModel):
    # Empty text means "use automatic defaults".  The public API can therefore
    # keep one request shape for both customized and default screening.
    text: str = Field(default="", max_length=2000)
    allowed_features: list[str] = Field(default_factory=list)


class SelectionStrategy(BaseModel):
    keep_per_group: int = Field(ge=1, le=10)
    strictness: str
    content_mode: str = "auto"
    weights: dict[str, float]
    constraints: dict[str, bool]
    unsupported_terms: list[str] = Field(default_factory=list)
    explanation: str


class ParseInstructionResponse(BaseModel):
    strategy: SelectionStrategy
    confidence: float
    fallback_used: bool
    provider: str
    model: str
