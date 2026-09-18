"""``GET /``: the page for trying the chat by hand."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx

from app.core.settings import Settings
from app.main import create_app
from app.resources import AppResources
from tests.unit.test_chat_api import FakeGateway
from tests.unit.test_health_api import FakeIndex


@asynccontextmanager
async def client_for(settings: Settings) -> AsyncIterator[httpx.AsyncClient]:
    async def resources(_: Settings) -> AppResources:
        return AppResources(index=FakeIndex(), gateway=FakeGateway())

    app = create_app(settings, resources_factory=resources)
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai.test"
        ) as client,
    ):
        yield client


async def test_the_page_is_served_self_contained_and_locked_to_this_origin() -> None:
    async with client_for(Settings()) as client:
        response = await client.get("/")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    policy = response.headers["content-security-policy"]
    assert "connect-src 'self'" in policy and "default-src 'none'" in policy
    assert response.headers["cache-control"] == "no-store"
    page = response.text
    assert 'fetch("/chat"' in page and 'fetch("/health/ready")' in page
    assert "http://" not in page and "https://" not in page  # loads nothing from anywhere else
    assert ".innerHTML" not in page  # replies are inserted as text, never as markup


async def test_the_page_can_be_turned_off() -> None:
    async with client_for(Settings(chat_page_enabled=False)) as client:
        response = await client.get("/")

    assert response.status_code == 404


async def test_the_page_is_not_part_of_the_api_description() -> None:
    async with client_for(Settings()) as client:
        schema = (await client.get("/openapi.json")).json()

    assert "/" not in schema["paths"]
    assert "/chat" in schema["paths"]
