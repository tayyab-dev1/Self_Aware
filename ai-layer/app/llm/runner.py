"""The one interface every model call goes through.

A call names a pinned model, a system prompt, the user-turn text and a JSON schema, and gets back
the JSON object the model produced for that schema. Nothing else about the transport leaks out, so
moving from the Claude CLI to the Anthropic SDK changes one file (CLAUDE.md "On the Claude CLI").

What comes back is still untrusted: callers parse it with ``extra="forbid"`` models and validate it.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

# Pinned, never an alias: a silent model update would change behaviour with no deploy.
DECOMPOSE_MODEL = "claude-haiku-4-5-20251001"
PLANNER_MODEL = "claude-sonnet-5"


@dataclass(frozen=True, slots=True)
class ModelRequest:
    model: str
    system: str
    prompt: str
    schema: Mapping[str, Any]
    # For logs and metrics only; never the prompt, which holds the user's sentence.
    purpose: str
    # False asks for an answer without extended thinking: much faster and steadier, for a call
    # simple enough not to need it. It changes what the model answers, so recordings keep it too.
    thinking: bool = True


class ModelError(Exception):
    """Base class for every model-call failure."""


class ModelUnavailableError(ModelError):
    """The model could not be called: the CLI is missing, timed out, or the service failed."""


class ModelOutputError(ModelError):
    """The model answered, but not with a JSON object for the schema."""


class StructuredModel(Protocol):
    async def generate(self, request: ModelRequest) -> dict[str, Any]: ...
