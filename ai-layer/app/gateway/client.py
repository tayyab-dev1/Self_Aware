"""Async HTTP client for the agent gateway.

Every response is parsed into a generated model before anything else sees it, so a backend that
drifts from the contract fails here, loudly, instead of somewhere downstream.
"""

from __future__ import annotations

from typing import Any, Self

import httpx
import structlog
from pydantic import BaseModel, ValidationError

from app.core.settings import Settings
from app.gateway.errors import GatewayProtocolError, GatewayRejectedError, GatewayUnavailableError
from app.gateway.models import (
    AgentErrorResponse,
    AgentMetadataResponse,
    CapabilityVersionsResponse,
    ExecuteRequest,
    ExecuteResponse,
    Plan,
    PreflightRequest,
    PreflightResponse,
    SessionCapabilitiesResponse,
)

log = structlog.get_logger(__name__)

METADATA_PATH = "/agent/metadata"
VERSIONS_PATH = "/agent/metadata/versions"
SESSION_CAPABILITIES_PATH = "/agent/session/capabilities"
PREFLIGHT_PATH = "/agent/preflight"
EXECUTE_PATH = "/agent/execute"


class GatewayClient:
    """Calls the backend's `/agent/*` endpoints and returns typed, validated models."""

    def __init__(self, http: httpx.AsyncClient) -> None:
        self._http = http

    @classmethod
    def from_settings(
        cls, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> Self:
        http = httpx.AsyncClient(
            base_url=str(settings.backend_base_url),
            timeout=settings.backend_timeout_seconds,
            headers={"Accept": "application/json"},
            transport=transport,
        )
        return cls(http)

    async def get_metadata(self) -> AgentMetadataResponse:
        """Every capability the backend exposes, with full detail (`GET /agent/metadata`)."""
        return _parse(await self._get(METADATA_PATH), AgentMetadataResponse)

    async def get_versions(self) -> CapabilityVersionsResponse:
        """Ids and versions only, for cheap change polling (`GET /agent/metadata/versions`)."""
        return _parse(await self._get(VERSIONS_PATH), CapabilityVersionsResponse)

    async def get_session_capabilities(self, user_token: str) -> SessionCapabilitiesResponse:
        """The capability ids this user may use: the allow-list (`GET /agent/session/capabilities`).

        The token is forwarded as a bearer credential and never logged.
        """
        response = await self._get(SESSION_CAPABILITIES_PATH, headers=_bearer(user_token))
        return _parse(response, SessionCapabilitiesResponse)

    async def preflight(self, plan: Plan, user_token: str) -> PreflightResponse:
        """Resolve, check, count and confirm a plan, for this user (`POST /agent/preflight`).

        Returns the confirmation the backend wrote and an opaque token for execute. Send the same
        plan back unchanged: the token carries its hash. A refusal raises `GatewayRejectedError`,
        whose `error` carries the candidates for `AMBIGUOUS_ENTITY` and the hint for
        `PRECONDITION_FAILED`. The token is never logged.
        """
        body = PreflightRequest(plan=plan).model_dump(mode="json", exclude_none=True)
        response = await self._send("POST", PREFLIGHT_PATH, headers=_bearer(user_token), json=body)
        return _parse(response, PreflightResponse)

    async def execute(
        self, plan: Plan, token: str, sentence: str, user_token: str
    ) -> ExecuteResponse:
        """Run a confirmed plan with the token its preflight returned (`POST /agent/execute`).

        Send the plan exactly as it went to preflight, and the user's own sentence for the audit
        trail. A refusal of the whole plan (an altered or expired token, a stale version) raises
        `GatewayRejectedError`. Once the plan is accepted the answer says, step by step, what
        succeeded, what was replayed, what failed with which error, and what did not run. The
        tokens are never logged.
        """
        body = ExecuteRequest(plan=plan, token=token, sentence=sentence).model_dump(
            mode="json", exclude_none=True
        )
        response = await self._send("POST", EXECUTE_PATH, headers=_bearer(user_token), json=body)
        return _parse(response, ExecuteResponse)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, headers: dict[str, str] | None = None) -> httpx.Response:
        return await self._send("GET", path, headers=headers)

    async def _send(
        self,
        method: str,
        path: str,
        headers: dict[str, str] | None = None,
        json: dict[str, Any] | None = None,
    ) -> httpx.Response:
        try:
            response = await self._http.request(method, path, headers=headers, json=json)
        except httpx.TimeoutException as exc:
            raise GatewayUnavailableError(f"Timed out calling the backend at {path}") from exc
        except httpx.TransportError as exc:
            raise GatewayUnavailableError(
                f"Could not reach the backend at {path} ({type(exc).__name__})"
            ) from exc

        if response.status_code != httpx.codes.OK:
            rejection = _rejection(path, response)
            expected = (
                response.is_client_error or response.status_code == httpx.codes.NOT_IMPLEMENTED
            )
            if isinstance(rejection, GatewayRejectedError) and expected:
                # An expected refusal such as AMBIGUOUS_ENTITY, or NOT_IMPLEMENTED (501) for a
                # capability declared but not built; the orchestrator decides.
                log.info(
                    "gateway_refused", path=path, status=response.status_code, code=rejection.code
                )
            else:
                log.warning("gateway_unexpected_status", path=path, status=response.status_code)
            raise rejection
        return response


def _bearer(user_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {user_token}"}


def _rejection(path: str, response: httpx.Response) -> GatewayRejectedError | GatewayProtocolError:
    try:
        error = AgentErrorResponse.model_validate_json(response.content)
    except ValidationError:
        return GatewayProtocolError(f"{path} returned HTTP {response.status_code}")
    return GatewayRejectedError(response.status_code, error)


def _parse[ModelT: BaseModel](response: httpx.Response, model: type[ModelT]) -> ModelT:
    try:
        return model.model_validate_json(response.content)
    except ValidationError as exc:
        path = response.request.url.path
        log.warning("gateway_contract_violation", path=path, errors=exc.error_count())
        raise GatewayProtocolError(
            f"{path} returned a body that does not match the contract "
            f"({exc.error_count()} validation error(s))"
        ) from exc
