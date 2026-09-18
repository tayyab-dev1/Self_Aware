"""Long-lived things the app opens at startup and closes at shutdown.

``app.main`` builds them in its lifespan and routes receive them through
``app.api.dependencies``. Tests swap in fakes by passing their own ``ResourcesFactory`` to
``create_app``, so nothing here reaches for globals.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Protocol

import httpx
import structlog

from app.core.settings import Settings
from app.decompose.decomposer import Decomposer
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.gateway.models import AgentMetadataResponse
from app.index.database import IndexDatabase, IndexProbe
from app.llm.claude_cli import ClaudeCliModel
from app.llm.runner import ModelUnavailableError
from app.orchestration.orchestrator import ChatOrchestrator
from app.orchestration.plan_cache import PlanCache
from app.orchestration.session import InMemorySessionStore
from app.planning.planner import Planner
from app.planning.service import SentencePlanner
from app.retrieval.hybrid import HybridRetriever
from app.sync.metadata_sync import MetadataSync
from domain.school.calendar import SCHOOL_TIME_ZONE, school_today
from domain.school.glossary import RECORD_WORDS, glossary_lines

log = structlog.get_logger(__name__)


class IndexStore(Protocol):
    """Anything that can report on the capability index."""

    async def probe(self) -> IndexProbe: ...

    async def close(self) -> None: ...


class Gateway(Protocol):
    """Anything that can answer the agent gateway's questions."""

    async def get_metadata(self) -> AgentMetadataResponse: ...

    async def aclose(self) -> None: ...


class Closeable(Protocol):
    async def aclose(self) -> None: ...


@dataclass(slots=True)
class AppResources:
    index: IndexStore
    gateway: Gateway
    embeddings: Closeable | None = None
    sync: MetadataSync | None = None
    retriever: HybridRetriever | None = None
    chat: ChatOrchestrator | None = None
    # Why chat is off while sync is on (for example, no Claude CLI); readiness reports it.
    chat_problem: str | None = None
    _sync_task: asyncio.Task[None] | None = field(default=None, init=False)

    def start(self) -> None:
        """Start background work: the metadata sync loop, when there is one."""
        if self.sync is not None and self._sync_task is None:
            self._sync_task = asyncio.create_task(self.sync.run_forever(), name="metadata-sync")

    async def aclose(self) -> None:
        try:
            if self._sync_task is not None:
                self._sync_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await self._sync_task
            if self.embeddings is not None:
                await self.embeddings.aclose()
            await self.gateway.aclose()
        finally:
            await self.index.close()


ResourcesFactory = Callable[[Settings], Awaitable[AppResources]]


async def build_resources(
    settings: Settings,
    *,
    backend_transport: httpx.AsyncBaseTransport | None = None,
    embeddings_transport: httpx.AsyncBaseTransport | None = None,
) -> AppResources:
    """Open the index pool (applying migrations if enabled), the gateway and embeddings clients.

    A database that is down stops startup. A backend or embeddings service that is down does not:
    the AI layer starts, sync keeps retrying, and readiness says what is missing.
    """
    index = await IndexDatabase.connect(settings)
    try:
        if settings.index_auto_migrate:
            applied = await index.apply_migrations()
            log.info("index_migrations_checked", applied_now=applied)
    except BaseException:
        await index.close()
        raise
    gateway = GatewayClient.from_settings(settings, transport=backend_transport)
    embeddings = EmbeddingsClient.from_settings(settings, transport=embeddings_transport)
    sync = (
        MetadataSync(
            gateway, index, embeddings, interval_seconds=settings.metadata_sync_interval_seconds
        )
        if settings.metadata_sync_enabled
        else None
    )
    retriever = HybridRetriever(
        index,
        embeddings,
        cap=settings.retrieval_candidate_cap,
        rrf_k=settings.retrieval_rrf_k,
        branch_limit=settings.retrieval_branch_limit,
    )
    chat, chat_problem = _build_chat(settings, gateway, retriever, sync)
    return AppResources(
        index=index,
        gateway=gateway,
        embeddings=embeddings,
        sync=sync,
        retriever=retriever,
        chat=chat,
        chat_problem=chat_problem,
    )


def _build_chat(
    settings: Settings,
    gateway: GatewayClient,
    retriever: HybridRetriever,
    sync: MetadataSync | None,
) -> tuple[ChatOrchestrator | None, str | None]:
    """Chat needs the catalog from sync and the Claude CLI. Without sync, chat is simply off."""
    if sync is None:
        return None, None
    try:
        model = ClaudeCliModel.from_settings(settings)
    except ModelUnavailableError as exc:
        log.warning("chat_unavailable", reason=str(exc))
        return None, str(exc)
    planner = SentencePlanner(
        Decomposer(model, glossary_lines()),
        retriever,
        Planner(
            model,
            max_steps=settings.plan_max_steps,
            today=school_today,
            time_zone=SCHOOL_TIME_ZONE,
            record_words=RECORD_WORDS,
        ),
        lambda: sync.catalog,
    )
    chat = ChatOrchestrator(
        gateway=gateway,
        planner=planner,
        catalog=lambda: sync.catalog,
        sessions=InMemorySessionStore(ttl_seconds=settings.chat_session_ttl_seconds),
        plan_cache=PlanCache(settings.plan_cache_size),
        today=school_today,
    )
    return chat, None
