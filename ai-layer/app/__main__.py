"""Run the AI layer server with the host and port from settings.

uv run python -m app        (or: make ai-run)
"""

from __future__ import annotations

import uvicorn

from app.core.settings import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "app.main:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
        access_log=False,  # RequestContextMiddleware logs every request with its request id
    )


if __name__ == "__main__":
    main()
