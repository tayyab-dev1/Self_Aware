from __future__ import annotations

import asyncio
import dataclasses
from collections.abc import Sequence

import pytest

from app.capabilities.snapshot import load_snapshot
from app.embeddings.client import UnexpectedEmbeddingModelError
from app.gateway.errors import GatewayUnavailableError
from app.gateway.models import (
    AgentMetadataResponse,
    CapabilityMetadata,
    CapabilityVersion,
    CapabilityVersionsResponse,
)
from app.index.database import CapabilityRow, IndexedVersion
from app.sync.metadata_sync import EMBEDDING_MODEL, MetadataSync

SNAPSHOT = load_snapshot()


class FakeBackend:
    def __init__(self, capabilities: Sequence[CapabilityMetadata]) -> None:
        self.capabilities = list(capabilities)
        self.metadata_calls = 0
        self.down = False

    async def get_versions(self) -> CapabilityVersionsResponse:
        if self.down:
            raise GatewayUnavailableError("backend down")
        return CapabilityVersionsResponse(
            versions=[CapabilityVersion(id=c.id, version=c.version) for c in self.capabilities]
        )

    async def get_metadata(self) -> AgentMetadataResponse:
        self.metadata_calls += 1
        return AgentMetadataResponse(capabilities=self.capabilities)

    def change(self, capability_id: str, **fields: object) -> None:
        self.capabilities = [
            c.model_copy(update=fields) if c.id == capability_id else c for c in self.capabilities
        ]


class FakeStore:
    def __init__(self) -> None:
        self.rows: dict[str, CapabilityRow] = {}
        self.writes = 0

    async def indexed_versions(self) -> dict[str, IndexedVersion]:
        return {
            row.capability_id: IndexedVersion(row.version, row.embedding_model)
            for row in self.rows.values()
        }

    async def apply_sync(self, upserts: Sequence[CapabilityRow], removals: Sequence[str]) -> None:
        self.writes += 1
        for row in upserts:
            self.rows[row.capability_id] = row
        for capability_id in removals:
            del self.rows[capability_id]


class FakeEmbedder:
    def __init__(self, *, wrong_model: bool = False) -> None:
        self.embedded: list[str] = []
        self.wrong_model = wrong_model

    async def require_expected_model(self) -> None:
        if self.wrong_model:
            raise UnexpectedEmbeddingModelError("another model")

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        self.embedded.extend(texts)
        return [[float(len(text)), 1.0] for text in texts]


def make_sync(
    backend: FakeBackend, store: FakeStore, embedder: FakeEmbedder | None = None
) -> tuple[MetadataSync, FakeEmbedder]:
    embedder = embedder or FakeEmbedder()
    return MetadataSync(backend, store, embedder, interval_seconds=0.01), embedder


async def test_the_first_sync_indexes_every_description_and_fills_the_catalog() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, embedder = make_sync(backend, store)

    report = await sync.sync_once()

    ids = sorted(c.id for c in SNAPSHOT.capabilities)
    assert list(report.added) == ids
    assert sorted(store.rows) == ids
    assert sorted(embedder.embedded) == sorted(c.description for c in SNAPSHOT.capabilities)
    cancellation = store.rows["fee.cancellation.raise"]
    assert cancellation.content.startswith("Proposes cancelling a fee charge")
    assert set(cancellation.disambiguate_from) == {
        "fee.credit.raise",
        "fee.latefee.waive",
        "fee.writeoff.propose",
    }
    assert cancellation.embedding_model == EMBEDDING_MODEL
    assert sorted(sync.catalog) == ids
    assert sync.status.indexed == len(ids)
    assert sync.status.last_success_at is not None


async def test_nothing_is_fetched_or_embedded_when_no_version_changed() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, embedder = make_sync(backend, store)
    await sync.sync_once()
    embedder.embedded.clear()

    report = await sync.sync_once()

    assert not report.changed
    assert report.unchanged == len(SNAPSHOT.capabilities)
    assert backend.metadata_calls == 1
    assert embedder.embedded == []
    assert store.writes == 1


async def test_a_new_version_re_embeds_only_that_capability() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, embedder = make_sync(backend, store)
    await sync.sync_once()
    embedder.embedded.clear()

    backend.change("fee.reminder.send", version="v2", description="Sends fee reminders. Rewritten.")
    report = await sync.sync_once()

    assert report.updated == ("fee.reminder.send",)
    assert embedder.embedded == ["Sends fee reminders. Rewritten."]
    assert store.rows["fee.reminder.send"].version == "v2"
    assert sync.catalog["fee.reminder.send"].version == "v2"


async def test_a_capability_the_backend_withdraws_is_deleted() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, _ = make_sync(backend, store)
    await sync.sync_once()

    backend.capabilities = [c for c in backend.capabilities if c.id != "dashboard.main.read"]
    report = await sync.sync_once()

    assert report.removed == ("dashboard.main.read",)
    assert "dashboard.main.read" not in store.rows
    assert "dashboard.main.read" not in sync.catalog
    assert sync.status.indexed == len(SNAPSHOT.capabilities) - 1


async def test_rows_from_another_embedding_model_are_re_embedded() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, _ = make_sync(backend, store)
    await sync.sync_once()
    store.rows["fee.overdue.list"] = dataclasses.replace(
        store.rows["fee.overdue.list"], embedding_model="old-model@abc"
    )

    report = await sync.sync_once()

    assert report.updated == ("fee.overdue.list",)
    assert store.rows["fee.overdue.list"].embedding_model == EMBEDDING_MODEL


async def test_the_catalog_is_loaded_on_start_even_when_the_index_is_already_current() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    await make_sync(backend, store)[0].sync_once()

    restarted, embedder = make_sync(backend, store)
    report = await restarted.sync_once()

    assert not report.changed
    assert embedder.embedded == []
    assert sorted(restarted.catalog) == sorted(c.id for c in SNAPSHOT.capabilities)


async def test_a_different_model_writes_nothing() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, _ = make_sync(backend, store, FakeEmbedder(wrong_model=True))

    with pytest.raises(UnexpectedEmbeddingModelError):
        await sync.sync_once()

    assert store.rows == {}
    assert sync.status.last_success_at is None
    assert sync.status.last_error is not None


async def test_a_failed_poll_keeps_the_last_success_and_the_loop_keeps_polling() -> None:
    backend, store = FakeBackend(SNAPSHOT.capabilities), FakeStore()
    sync, _ = make_sync(backend, store)
    await sync.sync_once()
    succeeded_at = sync.status.last_success_at
    backend.down = True

    loop = asyncio.create_task(sync.run_forever())
    await asyncio.sleep(0.05)
    backend.down = False
    backend.change("fee.overdue.list", version="v9")
    await asyncio.sleep(0.05)
    loop.cancel()

    assert store.rows["fee.overdue.list"].version == "v9"
    assert sync.status.last_error is None
    assert sync.status.last_success_at is not None
    assert succeeded_at is not None and sync.status.last_success_at > succeeded_at
