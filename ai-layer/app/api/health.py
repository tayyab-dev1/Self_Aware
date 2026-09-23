"""Health checks.

``/health/live``   the process is up.
``/health/ready``  the capability index is usable (pgvector works, table migrated), the
                   backend's ``GET /agent/metadata`` answers with a valid body, and (when sync
                   is enabled) metadata sync has filled the index at least once since start
                   and chat can call the models.
"""

from __future__ import annotations

import asyncio
from typing import Literal

import structlog
from fastapi import APIRouter, Response, status
from pydantic import BaseModel, ConfigDict

from app.api.dependencies import Resources
from app.gateway.errors import GatewayError
from app.resources import Gateway, IndexStore
from app.sync.metadata_sync import MetadataSync

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/health", tags=["health"])


class LiveResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["ok"]


class CheckResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    ok: bool
    detail: str


class ReadinessResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["ready", "not_ready"]
    checks: dict[str, CheckResult]


@router.get("/live")
async def live() -> LiveResponse:
    return LiveResponse(status="ok")


@router.get(
    "/ready",
    responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ReadinessResponse}},
)
async def ready(response: Response, resources: Resources) -> ReadinessResponse:
    index_check, backend_check = await asyncio.gather(
        _check_index(resources.index), _check_backend(resources.gateway)
    )
    checks = {"index_database": index_check, "backend_metadata": backend_check}
    if resources.sync is not None:
        checks["metadata_sync"] = _check_sync(resources.sync)
    if resources.chat is not None:
        chooser = "on" if resources.chooser_model is not None else "off"
        english = "the local translator" if resources.translator is not None else "Gemini"
        checks["chat"] = CheckResult(
            ok=True,
            detail=f"a Gemini API key is set; POST /chat is on; the chooser (Jev) is {chooser}; "
            f"the search query comes from {english}",
        )
    elif resources.chat_problem is not None:
        checks["chat"] = CheckResult(
            ok=False, detail=f"POST /chat is off: {resources.chat_problem}"
        )
    is_ready = all(check.ok for check in checks.values())
    if not is_ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return ReadinessResponse(status="ready" if is_ready else "not_ready", checks=checks)


async def _check_index(index: IndexStore) -> CheckResult:
    try:
        probe = await index.probe()
    except Exception as exc:  # a probe reports failures, it never raises them
        log.warning("readiness_index_unavailable", error=type(exc).__name__)
        return CheckResult(ok=False, detail=f"index database unavailable ({type(exc).__name__})")
    if not probe.capability_index_present:
        return CheckResult(
            ok=False, detail="capability_index table is missing: run `make ai-migrate`"
        )
    return CheckResult(
        ok=True,
        detail=(
            f"pgvector ok ({probe.vector_literal}), capability_index present, "
            f"migrations applied: {', '.join(probe.applied_migrations) or 'none'}"
        ),
    )


async def _check_backend(gateway: Gateway) -> CheckResult:
    try:
        metadata = await gateway.get_metadata()
    except GatewayError as exc:
        log.warning("readiness_backend_unavailable", error=type(exc).__name__, reason=str(exc))
        return CheckResult(ok=False, detail=f"GET /agent/metadata failed: {exc}")
    except Exception as exc:  # a probe reports failures, it never raises them
        log.warning("readiness_backend_unavailable", error=type(exc).__name__)
        return CheckResult(ok=False, detail=f"GET /agent/metadata failed ({type(exc).__name__})")
    return CheckResult(
        ok=True,
        detail=f"GET /agent/metadata answered with {len(metadata.capabilities)} capabilities",
    )


def _check_sync(sync: MetadataSync) -> CheckResult:
    status = sync.status
    if status.last_success_at is None:
        reason = status.last_error or "still running"
        return CheckResult(ok=False, detail=f"the first metadata sync has not finished: {reason}")
    detail = (
        f"{status.indexed} capabilities indexed, last synced "
        f"{status.last_success_at.isoformat(timespec='seconds')}"
    )
    # The index keeps serving what it last synced, so a later failure is reported, not fatal.
    if status.last_error is not None:
        detail += f"; the latest poll failed: {status.last_error}"
    return CheckResult(ok=True, detail=detail)
