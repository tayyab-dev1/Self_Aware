"""The one interface every model call goes through.

A call names a pinned model, a system prompt, the user-turn text and a JSON schema, and gets back
the JSON object the model produced for that schema. Nothing else about the transport leaks out, so
changing the model provider changes one file (today ``app/llm/gemini.py``; CLAUDE.md "On the model
calls").

What comes back is still untrusted: callers parse it with ``extra="forbid"`` models and validate it.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

# Pinned, never an alias such as "-latest": a silent model update would change behaviour with no
# deploy. Both calls use the same model for now; they stay two names so either can move alone.
DECOMPOSE_MODEL = "gemini-3.1-flash-lite"
PLANNER_MODEL = "gemini-3.1-flash-lite"


@dataclass(frozen=True, slots=True)
class ModelRequest:
    model: str
    system: str
    prompt: str
    schema: Mapping[str, Any]
    # For logs and metrics only; never the prompt, which holds the user's sentence.
    purpose: str
    # False asks for an answer with as little thinking as the model allows: much faster and
    # steadier, for a call simple enough not to need it. It changes what the model answers, so
    # recordings keep it too.
    thinking: bool = True


class ModelError(Exception):
    """Base class for every model-call failure."""


class ModelUnavailableError(ModelError):
    """The model could not be called: the CLI is missing, timed out, or the service failed."""


class ModelOutputError(ModelError):
    """The model answered, but not with a JSON object for the schema."""


class StructuredModel(Protocol):
    async def generate(self, request: ModelRequest) -> dict[str, Any]: ...
