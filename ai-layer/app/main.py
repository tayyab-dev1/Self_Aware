"""Builds the FastAPI application: logging, shared resources, middleware and routes."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from app.api import chat, chat_page, health
from app.api.middleware import RequestContextMiddleware
from app.core.logging import configure_logging
from app.core.settings import Settings, get_settings
from app.resources import ResourcesFactory, build_resources

log = structlog.get_logger(__name__)


def create_app(
    settings: Settings | None = None,
    resources_factory: ResourcesFactory = build_resources,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_format)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        resources = await resources_factory(settings)
        app.state.resources = resources
        resources.start()
        log.info(
            "ai_layer_started",
            port=settings.port,
            backend=str(settings.backend_base_url),
            index_db=f"{settings.index_db_host}:{settings.index_db_port}/{settings.index_db_name}",
        )
        try:
            yield
        finally:
            await resources.aclose()
            log.info("ai_layer_stopped")

    app = FastAPI(
        title="AI layer",
        version="0.1.0",
        summary="Turns a sentence into a plan the backend checks and runs.",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.add_middleware(RequestContextMiddleware)
    app.include_router(health.router)
    app.include_router(chat.router)
    if settings.chat_page_enabled:
        app.include_router(chat_page.router)
    return app
