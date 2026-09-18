"""Remembers what planning decided for a sentence, so repeating it costs no model call.

Plans are cached, never results (CLAUDE.md invariant 12): a cached plan still goes through
preflight and execute, which read today's data. The key holds everything that could change the
plan: the normalised sentence, the user's allow-list, every capability's version, and today's date
(the planner turns "aaj" into a date). A failed model call is never cached.
"""

from __future__ import annotations

import hashlib
import json
from collections import OrderedDict
from collections.abc import Collection, Mapping
from datetime import date

from app.gateway.models import CapabilityMetadata
from app.planning.outcomes import PlanOutcome
from app.validation.problems import normalise


def plan_cache_key(
    sentence: str,
    allowed: Collection[str],
    catalog: Mapping[str, CapabilityMetadata],
    today: date,
) -> str:
    material = {
        "sentence": normalise(sentence).strip(".!?"),
        "allowed": sorted(allowed),
        "versions": sorted((capability.id, capability.version) for capability in catalog.values()),
        "today": today.isoformat(),
    }
    return hashlib.sha256(json.dumps(material, separators=(",", ":")).encode()).hexdigest()


class PlanCache:
    """A small least-recently-used cache. Size 0 turns it off."""

    def __init__(self, size: int) -> None:
        self._size = size
        self._outcomes: OrderedDict[str, PlanOutcome] = OrderedDict()

    def get(self, key: str) -> PlanOutcome | None:
        outcome = self._outcomes.get(key)
        if outcome is not None:
            self._outcomes.move_to_end(key)
        return outcome

    def put(self, key: str, outcome: PlanOutcome) -> None:
        if self._size == 0:
            return
        self._outcomes[key] = outcome
        self._outcomes.move_to_end(key)
        while len(self._outcomes) > self._size:
            self._outcomes.popitem(last=False)

    def discard(self, key: str | None) -> None:
        if key is not None:
            self._outcomes.pop(key, None)

    def clear(self) -> None:
        self._outcomes.clear()

    def __len__(self) -> int:
        return len(self._outcomes)
