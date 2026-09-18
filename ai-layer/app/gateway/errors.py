"""What can go wrong when calling the agent gateway."""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.gateway.models import AgentErrorResponse


class GatewayError(Exception):
    """Base class for every gateway failure."""


class GatewayUnavailableError(GatewayError):
    """The backend could not be reached: connection refused, DNS failure, or timeout."""


class GatewayProtocolError(GatewayError):
    """The backend answered, but not with what the contract promises."""


class GatewayRejectedError(GatewayError):
    """The backend refused the request with an error code (CLAUDE.md, "Error codes").

    The orchestrator branches on `code`. `error` is the whole body: for `AMBIGUOUS_ENTITY` it
    carries the candidates to ask about, for `PRECONDITION_FAILED` the hint to show, and for any
    refusal inside a plan the step (and parameter) it is about. At execute, a refusal of one step
    is not raised: it arrives inside the response, with the same body.
    """

    def __init__(self, status: int, error: AgentErrorResponse) -> None:
        super().__init__(f"{error.code} (HTTP {status}): {error.message}")
        self.status = status
        self.error = error
        self.code = error.code
        self.message = error.message
