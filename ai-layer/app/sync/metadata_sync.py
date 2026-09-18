"""Keeps the capability index in step with the backend's metadata.

Every poll asks the backend for ids and versions only (cheap). A capability whose version, or whose
embedding model, differs from its index row is fetched in full and its description re-embedded; a
capability the backend no longer lists is deleted. Embedding happens before any database
connection is taken, so no connection waits on the model (CLAUDE.md invariant 10).

The latest full metadata is also kept in memory as the catalog, for the steps that need more than
an id: the planner reads parameters and effects from it.
"""

from __future__ import annotations

import asyncio
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol

import structlog

from app.embeddings.client import EXPECTED_MODEL_ID, EXPECTED_MODEL_REVISION
from app.gateway.models import (
    AgentMetadataResponse,
    CapabilityMetadata,
    CapabilityVersionsResponse,
)
from app.index.database import CapabilityRow, IndexedVersion

log = structlog.get_logger(__name__)

# Written into every row, so a model change re-embeds everything instead of mixing vector spaces.
EMBEDDING_MODEL = f"{EXPECTED_MODEL_ID}@{EXPECTED_MODEL_REVISION}"


class MetadataSource(Protocol):
    async def get_versions(self) -> CapabilityVersionsResponse: ...

    async def get_metadata(self) -> AgentMetadataResponse: ...


class DescriptionEmbedder(Protocol):
    async def require_expected_model(self) -> None: ...

    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class SyncStore(Protocol):
    async def indexed_versions(self) -> dict[str, IndexedVersion]: ...

    async def apply_sync(
        self, upserts: Sequence[CapabilityRow], removals: Sequence[str]
    ) -> None: ...


@dataclass(frozen=True, slots=True)
class SyncReport:
    """What one poll changed. Ids are sorted."""

    added: tuple[str, ...] = ()
    updated: tuple[str, ...] = ()
    removed: tuple[str, ...] = ()
    unchanged: int = 0

    @property
    def changed(self) -> bool:
        return bool(self.added or self.updated or self.removed)


@dataclass(slots=True)
class SyncStatus:
    """What readiness reports about sync."""

    last_success_at: datetime | None = None
    last_error: str | None = None
    indexed: int = 0
    catalog: Mapping[str, CapabilityMetadata] = field(default_factory=dict)


class MetadataSync:
    def __init__(
        self,
        source: MetadataSource,
        store: SyncStore,
        embedder: DescriptionEmbedder,
        *,
        interval_seconds: float = 30.0,
    ) -> None:
        self._source = source
        self._store = store
        self._embedder = embedder
        self._interval = interval_seconds
        self._lock = asyncio.Lock()
        self.status = SyncStatus()

    @property
    def catalog(self) -> Mapping[str, CapabilityMetadata]:
        """The full metadata of every capability the index holds, as of the last sync."""
        return self.status.catalog

    async def sync_once(self) -> SyncReport:
        """Poll once and bring the index up to date. Raises whatever stopped it."""
        async with self._lock:
            try:
                report = await self._sync()
            except Exception as exc:
                self.status.last_error = f"{type(exc).__name__}: {exc}"
                raise
            self.status.last_success_at = datetime.now(UTC)
            self.status.last_error = None
            return report

    async def run_forever(self) -> None:
        """Poll every interval until cancelled. A failed poll is logged and retried next time."""
        while True:
            try:
                report = await self.sync_once()
                if report.changed:
                    log.info(
                        "metadata_sync_changed",
                        added=list(report.added),
                        updated=list(report.updated),
                        removed=list(report.removed),
                        unchanged=report.unchanged,
                    )
            except Exception as exc:  # the loop must survive a backend or model outage
                log.warning("metadata_sync_failed", error=type(exc).__name__, reason=str(exc))
            await asyncio.sleep(self._interval)

    async def _sync(self) -> SyncReport:
        polled = await self._source.get_versions()
        versions = {entry.id: entry.version for entry in polled.versions}
        indexed = await self._store.indexed_versions()

        stale = sorted(
            capability_id
            for capability_id, version in versions.items()
            if indexed.get(capability_id) != IndexedVersion(version, EMBEDDING_MODEL)
        )
        removed = sorted(set(indexed) - set(versions))
        catalog_versions = {
            capability_id: metadata.version for capability_id, metadata in self.catalog.items()
        }

        catalog = {k: v for k, v in self.catalog.items() if k in versions}
        entries: list[CapabilityMetadata] = []
        if stale or catalog_versions != versions:
            metadata = await self._source.get_metadata()
            catalog = {capability.id: capability for capability in metadata.capabilities}
            # Written from the full metadata, so a row's version always matches its content. A
            # capability that changed again between the two calls is caught by the next poll.
            entries = [catalog[stale_id] for stale_id in stale if stale_id in catalog]

        rows: list[CapabilityRow] = []
        if entries:
            await self._embedder.require_expected_model()
            vectors = await self._embedder.embed([entry.description for entry in entries])
            rows = [
                CapabilityRow(
                    capability_id=entry.id,
                    content=entry.description,
                    module=entry.module,
                    read_only=entry.read_only,
                    embedding=vector,
                    version=entry.version,
                    disambiguate_from=tuple(entry.disambiguate_from),
                    embedding_model=EMBEDDING_MODEL,
                )
                for entry, vector in zip(entries, vectors, strict=True)
            ]
        if rows or removed:
            await self._store.apply_sync(rows, removed)

        written = {row.capability_id for row in rows}
        self.status.catalog = catalog
        self.status.indexed = len((set(indexed) - set(removed)) | written)
        return SyncReport(
            added=tuple(sorted(written - set(indexed))),
            updated=tuple(sorted(written & set(indexed))),
            removed=tuple(removed),
            unchanged=len(versions) - len(stale),
        )
