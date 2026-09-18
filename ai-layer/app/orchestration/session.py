"""A conversation's state between turns, and where it is kept.

Only the waiting states outlive a turn: ``IDLE``, ``AWAITING_INPUT`` and ``AWAITING_CONFIRM``.
``PLANNING``, ``EXECUTING`` and ``RESPONDING`` exist only while a turn is being handled. A session
belongs to the user who started it; another user naming its id gets a fresh session instead.

The store is an in-memory dict for the POC, behind ``SessionStore`` so Redis can replace it.
"""

from __future__ import annotations

import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Literal, Protocol

from app.gateway.models import EntityCandidate, Plan


class Phase(StrEnum):
    IDLE = "idle"
    PLANNING = "planning"
    AWAITING_INPUT = "awaiting_input"
    AWAITING_CONFIRM = "awaiting_confirm"
    EXECUTING = "executing"
    RESPONDING = "responding"


@dataclass(slots=True)
class PendingQuestion:
    """What the last question asked, so the answer can be put straight into the plan.

    ``choice``  pick one of the backend's candidates for a looked-up parameter
    ``words``   say the name again, after the backend found nothing
    ``value``   give a value the sentence left out
    """

    kind: Literal["choice", "words", "value"]
    step: int
    param: str
    options: tuple[EntityCandidate, ...] = ()
    attempts: int = 1


@dataclass(slots=True)
class Session:
    id: str
    user_id: str
    phase: Phase = Phase.IDLE
    sentence: str | None = None
    plan: Plan | None = None
    plan_cache_key: str | None = None
    missing: list[str] = field(default_factory=list)
    pending: PendingQuestion | None = None
    token: str | None = None
    touched_at: float = field(default_factory=time.monotonic)

    @classmethod
    def start(cls, user_id: str) -> Session:
        return cls(id=uuid.uuid4().hex, user_id=user_id)

    def reset(self) -> None:
        """Back to idle, forgetting any plan, question or confirmation."""
        self.phase = Phase.IDLE
        self.sentence = None
        self.plan = None
        self.plan_cache_key = None
        self.missing = []
        self.pending = None
        self.token = None


class SessionStore(Protocol):
    def get(self, session_id: str) -> Session | None: ...

    def put(self, session: Session) -> None: ...


class InMemorySessionStore:
    def __init__(self, *, ttl_seconds: float, max_sessions: int = 10_000) -> None:
        self._ttl = ttl_seconds
        self._max = max_sessions
        self._sessions: OrderedDict[str, Session] = OrderedDict()

    def get(self, session_id: str) -> Session | None:
        session = self._sessions.get(session_id)
        if session is None:
            return None
        if time.monotonic() - session.touched_at > self._ttl:
            del self._sessions[session_id]
            return None
        return session

    def put(self, session: Session) -> None:
        session.touched_at = time.monotonic()
        self._sessions[session.id] = session
        self._sessions.move_to_end(session.id)
        while len(self._sessions) > self._max:
            self._sessions.popitem(last=False)
