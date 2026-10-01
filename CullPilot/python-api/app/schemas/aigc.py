from pydantic import BaseModel, Field


class AigcEditRequest(BaseModel):
    """Request sent by Spring Boot for one source image edit."""

    source_path: str = Field(min_length=1, max_length=500)
    prompt: str = Field(default="", max_length=2000)
    model: str | None = Field(default=None, max_length=100)
    size: str = Field(default="1024*1024", pattern=r"^\d{3,4}\*\d{3,4}$")
    prompt_extend: bool = True
    watermark: bool = False


class AigcEditResponse(BaseModel):
    image_base64: str
    mime_type: str
    prompt_used: str
    provider: str
    model: str
