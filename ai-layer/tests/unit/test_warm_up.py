"""The startup warm-ups: cheap first calls that run in the background and never stop the service."""

from __future__ import annotations

import asyncio

from app.gateway.models import AgentMetadataResponse
from app.index.database import IndexProbe
from app.resources import AppResources, warm_up


class FakeIndex:
    async def probe(self) -> IndexProbe:  # pragma: no cover - not probed here
        raise AssertionError

    async def close(self) -> None:
        return None


class FakeGateway:
    async def get_metadata(self) -> AgentMetadataResponse:  # pragma: no cover
        raise AssertionError

    async def aclose(self) -> None:
        return None


async def test_every_warm_up_runs_and_a_failing_one_does_not_stop_the_others() -> None:
    ran: list[str] = []

    async def fine() -> None:
        ran.append("embeddings")

    async def broken() -> None:
        raise ConnectionError("down")

    await warm_up({"embeddings": fine, "jev": broken})

    assert ran == ["embeddings"]


async def test_the_warm_ups_start_with_the_app_and_are_cancelled_at_shutdown() -> None:
    started = asyncio.Event()

    async def slow() -> None:
        started.set()
        await asyncio.sleep(3600)

    resources = AppResources(index=FakeIndex(), gateway=FakeGateway(), warm_ups={"gemini": slow})
    resources.start()
    await asyncio.wait_for(started.wait(), 1)

    await asyncio.wait_for(resources.aclose(), 1)  # does not wait an hour
