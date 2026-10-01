import sys
from pathlib import Path

from fastapi import FastAPI


# The algorithm module lives at the repository root and is shared by this API.
repository_root = Path(__file__).resolve().parents[3]
if str(repository_root) not in sys.path:
    sys.path.insert(0, str(repository_root))

from app.api.routes.health import router as health_router
from app.api.routes.llm import router as llm_router
from app.api.routes.analysis import router as analysis_router
from app.api.routes.aigc import router as aigc_router
from app.config import settings

app = FastAPI(
    title="CullPilot Python API Adapter",
    version="0.1.0",
    description="External AI/API adapter boundary for the CullPilot backend.",
)

app.include_router(health_router, prefix="/api/v1")
app.include_router(llm_router, prefix="/api/v1")
app.include_router(analysis_router, prefix="/api/v1")
app.include_router(aigc_router, prefix="/api/v1")


@app.get("/")
async def root() -> dict[str, str]:
    return {
        "service": settings.service_name,
        "status": "ok",
    }
