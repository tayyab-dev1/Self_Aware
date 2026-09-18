"""The real startup path: connect to the index, migrate it, and report ready."""

from __future__ import annotations

import asyncio
import functools
import json
from pathlib import Path
from typing import Any

import asyncpg
import httpx

from app.capabilities.snapshot import SNAPSHOT_PATH
from app.embeddings.client import DIMENSIONS, EXPECTED_MODEL_ID, EXPECTED_MODEL_REVISION
from app.main import create_app
from app.resources import build_resources
from tests.integration.conftest import PostgresServer


def fake_backend(request: httpx.Request) -> httpx.Response:
    if request.method == "GET" and request.url.path == "/agent/metadata":
        return httpx.Response(200, json={"capabilities": []})
    return httpx.Response(404)


async def test_startup_migrates_the_index_and_reports_ready(
    postgres_server: PostgresServer,
) -> None:
    settings = postgres_server.ai_settings(
        backend_base_url="http://backend.test", metadata_sync_enabled=False
    )
    factory = functools.partial(
        build_resources, backend_transport=httpx.MockTransport(fake_backend)
    )
    app = create_app(settings, resources_factory=factory)

    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai-layer.test"
        ) as client,
    ):
        response = await client.get("/health/ready")

    body = response.json()
    assert response.status_code == 200, body
    assert body["checks"]["index_database"]["ok"] is True
    assert "migrations applied: 0001, 0002" in body["checks"]["index_database"]["detail"]
    assert body["checks"]["backend_metadata"]["ok"] is True
    assert "metadata_sync" not in body["checks"]


def snapshot_backend(request: httpx.Request) -> httpx.Response:
    """Serves the committed metadata snapshot, versions and all."""
    body = json.loads(SNAPSHOT_PATH.read_text())
    if request.url.path == "/agent/metadata":
        return httpx.Response(200, json=body)
    if request.url.path == "/agent/metadata/versions":
        versions = [{"id": c["id"], "version": c["version"]} for c in body["capabilities"]]
        return httpx.Response(200, json={"versions": versions})
    return httpx.Response(404)


def fake_embeddings(request: httpx.Request) -> httpx.Response:
    """Stands in for the embeddings service: the pinned model, one fixed vector per text."""
    if request.url.path == "/info":
        return httpx.Response(
            200, json={"model_id": EXPECTED_MODEL_ID, "model_sha": EXPECTED_MODEL_REVISION}
        )
    texts = json.loads(request.content)["inputs"]
    return httpx.Response(200, json=[[1.0] + [0.0] * (DIMENSIONS - 1) for _ in texts])


async def test_startup_syncs_the_backend_metadata_into_the_index_before_reporting_ready(
    postgres_server: PostgresServer, superuser_connection: asyncpg.Connection, tmp_path: Path
) -> None:
    cli = tmp_path / "claude"  # never run here; chat only needs to find it
    cli.write_text("#!/bin/sh\nexit 1\n")
    settings = postgres_server.ai_settings(
        backend_base_url="http://backend.test",
        embeddings_base_url="http://embeddings.test",
        claude_cli_path=str(cli),
    )
    factory = functools.partial(
        build_resources,
        backend_transport=httpx.MockTransport(snapshot_backend),
        embeddings_transport=httpx.MockTransport(fake_embeddings),
    )
    app = create_app(settings, resources_factory=factory)

    try:
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://ai-layer.test"
            ) as client,
        ):
            body = await _ready_body(client)
            resources = app.state.resources
            assert resources.sync is not None and resources.retriever is not None
            result = await resources.retriever.retrieve(
                ["send a fee reminder"], list(resources.sync.catalog)
            )
        indexed = await superuser_connection.fetch(
            "SELECT capability_id, version FROM ai_layer.capability_index ORDER BY 1"
        )
    finally:
        await superuser_connection.execute("DELETE FROM ai_layer.capability_index")

    snapshot = json.loads(SNAPSHOT_PATH.read_text())["capabilities"]
    assert body["checks"]["metadata_sync"]["ok"] is True
    assert body["checks"]["metadata_sync"]["detail"].startswith("8 capabilities indexed")
    assert body["checks"]["chat"]["ok"] is True
    assert [tuple(r) for r in indexed] == sorted((c["id"], c["version"]) for c in snapshot)
    assert "fee.reminder.send" in result.capability_ids


async def _ready_body(client: httpx.AsyncClient) -> dict[str, Any]:
    for _ in range(100):
        response = await client.get("/health/ready")
        if response.status_code == 200:
            body: dict[str, Any] = response.json()
            return body
        await asyncio.sleep(0.1)
    raise AssertionError(f"never became ready: {response.json()}")
