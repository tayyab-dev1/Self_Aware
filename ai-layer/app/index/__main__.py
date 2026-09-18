"""Apply the capability index migrations without starting the server.

uv run python -m app.index        (or: make ai-migrate)
"""

from __future__ import annotations

import asyncio

import structlog

from app.core.logging import configure_logging
from app.core.settings import get_settings
from app.index.database import IndexDatabase


async def _migrate() -> None:
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_format)
    log = structlog.get_logger("app.index")

    database = await IndexDatabase.connect(settings)
    try:
        applied = await database.apply_migrations()
        probe = await database.probe()
    finally:
        await database.close()
    log.info(
        "index_migrations_done",
        schema=database.schema,
        applied_now=applied,
        applied_total=list(probe.applied_migrations),
    )


if __name__ == "__main__":
    asyncio.run(_migrate())
