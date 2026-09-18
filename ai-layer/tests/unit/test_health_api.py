from __future__ import annotations

import asyncio
import socket
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime

import httpx
import pytest

from app.core.settings import Settings
from app.gateway.client import GatewayClient
from app.index.database import IndexProbe
from app.main import create_app
from app.resources import AppResources
from app.sync.metadata_sync import MetadataSync

HEALTHY = IndexProbe(
    vector_literal="[1,2,3]", capability_index_present=True, applied_migrations=("0001",)
)
EMPTY_METADATA: dict[str, list[object]] = {"capabilities": []}


class FakeIndex:
    def __init__(self, probe: IndexProbe | Exception = HEALTHY) -> None:
        self._probe = probe
        self.closed = False

    async def probe(self) -> IndexProbe:
        if isinstance(self._probe, Exception):
            raise self._probe
        return self._probe

    async def close(self) -> None:
        self.closed = True


def backend_returning(response: httpx.Response) -> Callable[[httpx.Request], httpx.Response]:
    return lambda _: response


def backend_refusing(request: httpx.Request) -> httpx.Response:
    raise httpx.ConnectError("connection refused", request=request)


@asynccontextmanager
async def running_app(
    index: FakeIndex,
    backend: Callable[[httpx.Request], httpx.Response],
    sync: MetadataSync | None = None,
) -> AsyncIterator[httpx.AsyncClient]:
    gateway = GatewayClient(
        httpx.AsyncClient(base_url="http://backend.test", transport=httpx.MockTransport(backend))
    )

    async def resources(_: Settings) -> AppResources:
        return AppResources(index=index, gateway=gateway, sync=sync)

    app = create_app(Settings(), resources_factory=resources)
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai-layer.test"
        ) as client,
    ):
        yield client


async def test_live_answers_ok() -> None:
    async with running_app(FakeIndex(), backend_refusing) as client:
        response = await client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_ready_when_the_index_works_and_the_backend_answers() -> None:
    backend = backend_returning(httpx.Response(200, json=EMPTY_METADATA))

    async with running_app(FakeIndex(), backend) as client:
        response = await client.get("/health/ready")

    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "ready"
    assert body["checks"]["index_database"]["ok"] is True
    assert "migrations applied: 0001" in body["checks"]["index_database"]["detail"]
    assert body["checks"]["backend_metadata"] == {
        "ok": True,
        "detail": "GET /agent/metadata answered with 0 capabilities",
    }


async def test_not_ready_while_the_backend_is_down() -> None:
    async with running_app(FakeIndex(), backend_refusing) as client:
        response = await client.get("/health/ready")

    body = response.json()
    assert response.status_code == 503
    assert body["status"] == "not_ready"
    assert body["checks"]["index_database"]["ok"] is True
    assert body["checks"]["backend_metadata"]["ok"] is False
    assert "Could not reach the backend" in body["checks"]["backend_metadata"]["detail"]


async def test_not_ready_when_the_backend_breaks_the_contract() -> None:
    backend = backend_returning(httpx.Response(200, json={"capabilities": [], "extra": 1}))

    async with running_app(FakeIndex(), backend) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert "does not match the contract" in response.json()["checks"]["backend_metadata"]["detail"]


async def test_not_ready_when_the_index_table_is_missing() -> None:
    index = FakeIndex(IndexProbe("[1,2,3]", capability_index_present=False, applied_migrations=()))
    backend = backend_returning(httpx.Response(200, json=EMPTY_METADATA))

    async with running_app(index, backend) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert "make ai-migrate" in response.json()["checks"]["index_database"]["detail"]


async def test_not_ready_when_the_index_database_fails() -> None:
    backend = backend_returning(httpx.Response(200, json=EMPTY_METADATA))

    async with running_app(FakeIndex(ConnectionRefusedError("down")), backend) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["index_database"] == {
        "ok": False,
        "detail": "index database unavailable (ConnectionRefusedError)",
    }


class StalledSource:
    """A backend whose versions never arrive, so the first sync never finishes."""

    async def get_versions(self) -> object:
        await asyncio.Event().wait()
        raise AssertionError("unreachable")

    async def get_metadata(self) -> object:
        raise AssertionError("unreachable")


class UnusedStore:
    async def indexed_versions(self) -> dict[str, object]:
        return {}

    async def apply_sync(self, upserts: object, removals: object) -> None:
        return None


class UnusedEmbedder:
    async def require_expected_model(self) -> None:
        return None

    async def embed(self, texts: object) -> list[list[float]]:
        return []


def sync_with(source: object) -> MetadataSync:
    return MetadataSync(source, UnusedStore(), UnusedEmbedder())  # type: ignore[arg-type]


async def test_not_ready_until_the_first_metadata_sync_finishes() -> None:
    backend = backend_returning(httpx.Response(200, json=EMPTY_METADATA))

    async with running_app(FakeIndex(), backend, sync_with(StalledSource())) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["metadata_sync"] == {
        "ok": False,
        "detail": "the first metadata sync has not finished: still running",
    }


async def test_a_failed_poll_after_a_good_sync_is_reported_but_still_ready() -> None:
    backend = backend_returning(httpx.Response(200, json=EMPTY_METADATA))
    sync = sync_with(StalledSource())
    sync.status.last_success_at = datetime(2026, 9, 14, 9, 0, tzinfo=UTC)
    sync.status.indexed = 8
    sync.status.last_error = "GatewayUnavailableError: backend down"

    async with running_app(FakeIndex(), backend, sync) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 200
    assert response.json()["checks"]["metadata_sync"] == {
        "ok": True,
        "detail": "8 capabilities indexed, last synced 2026-09-14T09:00:00+00:00; "
        "the latest poll failed: GatewayUnavailableError: backend down",
    }


async def test_a_well_formed_request_id_is_echoed_back() -> None:
    async with running_app(FakeIndex(), backend_refusing) as client:
        response = await client.get("/health/live", headers={"X-Request-ID": "req-123"})

    assert response.headers["x-request-id"] == "req-123"


async def test_a_malformed_request_id_is_replaced() -> None:
    async with running_app(FakeIndex(), backend_refusing) as client:
        response = await client.get(
            "/health/live", headers={"X-Request-ID": "bad id\nwith newline"}
        )

    assert response.headers["x-request-id"] != "bad id\nwith newline"
    assert len(response.headers["x-request-id"]) == 32


async def test_resources_are_closed_on_shutdown() -> None:
    index = FakeIndex()

    async with running_app(index, backend_refusing):
        assert index.closed is False

    assert index.closed is True


def _unused_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


async def test_startup_fails_when_the_index_database_is_unreachable() -> None:
    settings = Settings(index_db_host="127.0.0.1", index_db_port=_unused_local_port())
    app = create_app(settings)

    with pytest.raises(OSError):
        async with app.router.lifespan_context(app):
            pass
