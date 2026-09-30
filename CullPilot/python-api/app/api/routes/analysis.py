from fastapi import APIRouter, HTTPException

from app.schemas.analysis import AnalyzeRequest, AnalyzeResponse

router = APIRouter(prefix="/internal", tags=["internal-analysis"])


@router.post("/analyze", response_model=AnalyzeResponse)
def analyze_project(request: AnalyzeRequest) -> AnalyzeResponse:
    try:
        from app.analysis.adapter import analyze

        return analyze(request)
    except ModuleNotFoundError as exc:
        raise HTTPException(status_code=503, detail="Analysis dependencies are not installed") from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
