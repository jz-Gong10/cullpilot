import asyncio

from app.api.routes.llm import parse_instruction_route
from app.schemas.llm import ParseInstructionRequest


def test_parse_instruction_without_analysis_dependencies() -> None:
    response = asyncio.run(parse_instruction_route(ParseInstructionRequest(
        text="keep 3 clear photos", allowed_features=["sharpness"])))
    assert response.strategy.keep_per_group >= 1


def test_parse_instruction_uses_defaults_for_empty_text() -> None:
    response = asyncio.run(parse_instruction_route(ParseInstructionRequest(text="", allowed_features=[])))
    assert response.strategy.content_mode == "auto"
