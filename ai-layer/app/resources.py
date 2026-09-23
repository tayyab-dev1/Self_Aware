"""Long-lived things the app opens at startup and closes at shutdown.

``app.main`` builds them in its lifespan and routes receive them through
``app.api.dependencies``. Tests swap in fakes by passing their own ``ResourcesFactory`` to
``create_app``, so nothing here reaches for globals.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from typing import Protocol

import httpx
import structlog

from app.choosing.chooser import CapabilityChooser
from app.choosing.jev import JevModel
from app.core.settings import Settings
from app.decompose.decomposer import Decomposer, IntentSource
from app.decompose.translator import TranslatorDecomposer
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.gateway.models import AgentMetadataResponse
from app.index.database import IndexDatabase, IndexProbe
from app.llm.gemini import GeminiModel
from app.llm.runner import DECOMPOSE_MODEL, ModelUnavailableError
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
    # The model client chat calls; closed at shutdown.
    model: Closeable | None = None
    # The capability chooser's client, when the chooser is on; closed at shutdown.
    chooser_model: Closeable | None = None
    # The translator client, when it writes the search query instead of Gemini; closed at shutdown.
    translator: Closeable | None = None
    # Why chat is off while sync is on (for example, no Gemini API key); readiness reports it.
    chat_problem: str | None = None
    # Cheap first calls to each outside service, run once in the background at startup, so the
    # first chat turn does not pay for a cold embedding model or new connections.
    warm_ups: Mapping[str, Callable[[], Awaitable[None]]] = field(default_factory=dict)
    _sync_task: asyncio.Task[None] | None = field(default=None, init=False)
    _warm_up_task: asyncio.Task[None] | None = field(default=None, init=False)

    def start(self) -> None:
        """Start background work: the metadata sync loop, when there is one, and the warm-ups."""
        if self.sync is not None and self._sync_task is None:
            self._sync_task = asyncio.create_task(self.sync.run_forever(), name="metadata-sync")
        if self.warm_ups and self._warm_up_task is None:
            self._warm_up_task = asyncio.create_task(warm_up(self.warm_ups), name="warm-up")

    async def aclose(self) -> None:
        try:
            for task in (self._sync_task, self._warm_up_task):
                if task is not None:
                    task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await task
            if self.embeddings is not None:
                await self.embeddings.aclose()
            if self.model is not None:
                await self.model.aclose()
            if self.chooser_model is not None:
                await self.chooser_model.aclose()
            if self.translator is not None:
                await self.translator.aclose()
            await self.gateway.aclose()
        finally:
            await self.index.close()


ResourcesFactory = Callable[[Settings], Awaitable[AppResources]]


async def warm_up(calls: Mapping[str, Callable[[], Awaitable[None]]]) -> None:
    """Run every warm-up at once. A failure is only logged: the real call will say what is wrong."""

    async def one(name: str, call: Callable[[], Awaitable[None]]) -> None:
        started = time.monotonic()
        try:
            await call()
        except Exception as exc:  # a warm-up must never stop the service
            log.warning("warm_up_failed", service=name, error=type(exc).__name__)
            return
        log.info("warmed_up", service=name, duration_ms=round((time.monotonic() - started) * 1000))

    await asyncio.gather(*(one(name, call) for name, call in calls.items()))


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
    chooser_model = _build_chooser_model(settings)
    translator = (
        TranslatorDecomposer.from_settings(settings)
        if settings.decompose_source == "translator"
        else None
    )
    chat, model, chat_problem = _build_chat(
        settings, gateway, retriever, sync, chooser_model, translator
    )
    warm_ups: dict[str, Callable[[], Awaitable[None]]] = {"embeddings": embeddings.warm_up}
    if translator is not None:
        warm_ups["translator"] = translator.warm_up
    if model is not None:
        warm_ups["gemini"] = lambda: model.warm_up(DECOMPOSE_MODEL)
    if chooser_model is not None:
        warm_ups["jev"] = chooser_model.warm_up
    return AppResources(
        index=index,
        gateway=gateway,
        embeddings=embeddings,
        sync=sync,
        retriever=retriever,
        chat=chat,
        model=model,
        chooser_model=chooser_model,
        translator=translator,
        chat_problem=chat_problem,
        warm_ups=warm_ups,
    )


def _build_chooser_model(settings: Settings) -> JevModel | None:
    """The chooser's client when the chooser is on; without a key, chat plans without it."""
    if not settings.chooser_enabled:
        return None
    try:
        return JevModel.from_settings(settings)
    except ModelUnavailableError as exc:
        log.warning("chooser_off", reason=str(exc))
        return None


def _build_chat(
    settings: Settings,
    gateway: GatewayClient,
    retriever: HybridRetriever,
    sync: MetadataSync | None,
    chooser_model: JevModel | None = None,
    translator: TranslatorDecomposer | None = None,
) -> tuple[ChatOrchestrator | None, GeminiModel | None, str | None]:
    """Chat needs the catalog from sync and a model. Without sync, chat is simply off."""
    if sync is None:
        return None, None, None
    try:
        model = GeminiModel.from_settings(settings)
    except ModelUnavailableError as exc:
        log.warning("chat_unavailable", reason=str(exc))
        return None, None, str(exc)
    intents: IntentSource = translator or Decomposer(model, glossary_lines())
    planner = SentencePlanner(
        intents,
        retriever,
        Planner(
            model,
            max_steps=settings.plan_max_steps,
            today=school_today,
            time_zone=SCHOOL_TIME_ZONE,
            record_words=RECORD_WORDS,
        ),
        lambda: sync.catalog,
        CapabilityChooser(chooser_model) if chooser_model is not None else None,
    )
    chat = ChatOrchestrator(
        gateway=gateway,
        planner=planner,
        catalog=lambda: sync.catalog,
        sessions=InMemorySessionStore(ttl_seconds=settings.chat_session_ttl_seconds),
        plan_cache=PlanCache(settings.plan_cache_size),
        today=school_today,
    )
    return chat, model, None
