from fastapi import APIRouter

from app.schemas.llm import ParseInstructionRequest, ParseInstructionResponse
from app.services.instruction_parser import parse_instruction

router = APIRouter(prefix="/internal/llm", tags=["llm-adapter"])


@router.post("/parse-instruction", response_model=ParseInstructionResponse)
async def parse_instruction_route(request: ParseInstructionRequest) -> ParseInstructionResponse:
    return parse_instruction(request.text, request.allowed_features)
