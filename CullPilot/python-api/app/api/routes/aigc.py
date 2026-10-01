from fastapi import APIRouter, HTTPException

from app.schemas.aigc import AigcEditRequest, AigcEditResponse
from app.services.aigc_service import AigcProviderError, edit_image


router = APIRouter(prefix="/internal/aigc", tags=["internal-aigc"])


@router.post("/image-edit", response_model=AigcEditResponse)
def image_edit(request: AigcEditRequest) -> AigcEditResponse:
    try:
        return edit_image(request)
    except AigcProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
