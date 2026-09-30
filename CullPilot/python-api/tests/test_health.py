import asyncio

from app.api.routes.health import health


def test_health() -> None:
    response = asyncio.run(health())
    assert response["status"] == "ok"
