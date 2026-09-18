"""Decompose, retrieve, plan, validate: the pipeline with scripted models."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import date
from typing import Any

import pytest

from app.capabilities.snapshot import load_snapshot
from app.core import trace
from app.decompose.decomposer import Decomposer
from app.llm.runner import DECOMPOSE_MODEL, PLANNER_MODEL, ModelRequest
from app.planning.outcomes import PlannedSteps, Refusal, RefusalReason
from app.planning.planner import Planner, candidate_entry
from app.planning.service import SentencePlanner
from app.retrieval.hybrid import HybridRetriever
from app.validation.problems import InvalidModelOutputError
from domain.school.glossary import glossary_lines

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
SENTENCE = "class 5 blue ke defaulters ko whatsapp par reminder bhejo"
INTENT = "Send a WhatsApp fee reminder to the defaulters in class 5 blue"


class ScriptedModels:
    """Answers decompose and plan calls from scripts, and keeps every request."""

    def __init__(self, plan: dict[str, Any], intent: str = INTENT, entities: Sequence[str] = ()):
        self.plan = plan
        self.intent = {"text": intent, "entities": list(entities)}
        self.requests: list[ModelRequest] = []

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        self.requests.append(request)
        if request.model == DECOMPOSE_MODEL:
            return {"intents": [self.intent]}
        return self.plan


class RankedIndex:
    """An index whose searches always rank these capabilities."""

    def __init__(self, ranked: Sequence[str]) -> None:
        self.ranked = list(ranked)
        self.queries: list[str] = []

    async def dense_ranking(
        self, vector: Sequence[float], allowed: Sequence[str], embedding_model: str, limit: int
    ) -> list[str]:
        return [c for c in self.ranked if c in allowed]

    async def lexical_ranking(self, text: str, allowed: Sequence[str], limit: int) -> list[str]:
        self.queries.append(text)
        return [c for c in self.ranked if c in allowed]

    async def siblings(self, allowed: Sequence[str]) -> Mapping[str, tuple[str, ...]]:
        return {c: tuple(CATALOG[c].disambiguate_from) for c in allowed if c in CATALOG}


class Embedder:
    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        return [[1.0] for _ in texts]


def pipeline(models: ScriptedModels, index: RankedIndex) -> SentencePlanner:
    return SentencePlanner(
        Decomposer(models, glossary_lines()),
        HybridRetriever(index, Embedder()),
        Planner(models, max_steps=3, today=lambda: date(2026, 9, 14), time_zone="Asia/Karachi"),
        lambda: CATALOG,
    )


REMINDER_PLAN = {
    "outcome": "plan",
    "steps": [
        {
            "capability_id": "fee.reminder.send",
            "params": [
                {"name": "section_id", "words": "class 5 blue"},
                {"name": "channel", "value": "whatsapp"},
            ],
        }
    ],
}


async def test_a_roman_urdu_sentence_is_planned_from_its_english_intents_and_candidates() -> None:
    models = ScriptedModels(REMINDER_PLAN, entities=["class 5 blue"])
    index = RankedIndex(["fee.reminder.send", "dashboard.main.read"])

    understanding = await pipeline(models, index).understand(
        SENTENCE, allowed=list(CATALOG), session_id="s-1"
    )

    assert isinstance(understanding.outcome, PlannedSteps)
    assert understanding.outcome.plan.steps[0].params["section_id"].raw == "class 5 blue"
    assert index.queries == [INTENT]  # retrieval searched with the English intent
    assert understanding.candidates == (
        "fee.reminder.send",
        "fee.overdue.list",
        "dashboard.main.read",
    )

    _, plan = models.requests
    assert plan.model == PLANNER_MODEL
    assert f"Message:\n{SENTENCE}" in plan.prompt
    assert f"Intents (a retrieval aid, not the plan):\n1. {INTENT}" in plan.prompt
    assert "Today: 2026-09-14 (Asia/Karachi)" in plan.prompt
    assert "Intents are a retrieval aid, not the plan." in plan.system
    shown = json.loads(plan.prompt.split("Candidates:\n", 1)[1])
    assert [entry["id"] for entry in shown] == list(understanding.candidates)


async def test_nothing_retrieved_is_refused_without_calling_the_planner() -> None:
    models = ScriptedModels(REMINDER_PLAN, intent="Ask about tomorrow's weather")

    understanding = await pipeline(models, RankedIndex([])).understand(
        "kal ka mausam kaisa hoga", allowed=list(CATALOG), session_id="s-1"
    )

    assert understanding.outcome == Refusal(RefusalReason.NO_MATCHING_CAPABILITY)
    assert [request.model for request in models.requests] == [DECOMPOSE_MODEL]


async def test_the_planner_cannot_reach_past_the_users_allow_list() -> None:
    models = ScriptedModels(REMINDER_PLAN)

    with pytest.raises(InvalidModelOutputError) as raised:
        await pipeline(models, RankedIndex(list(CATALOG))).understand(
            SENTENCE, allowed=["fee.overdue.list", "dashboard.main.read"], session_id="s-1"
        )

    assert raised.value.codes == ["NOT_ALLOWED"]


def test_a_candidate_shows_the_planner_what_it_needs_and_marks_looked_up_parameters() -> None:
    entry = candidate_entry(CATALOG["fee.reminder.send"])

    assert entry["id"] == "fee.reminder.send"
    assert entry["changes_data"] is True
    assert entry["publishes"] == ["guardians", "total_outstanding"]
    section, channel = entry["parameters"]
    assert section["looked_up_from_words"] is True
    assert channel == {
        "name": "channel",
        "type": "string",
        "required": True,
        "meaning": "How the reminder is delivered",
        "allowed": ["whatsapp", "sms", "email"],
        "default": "whatsapp",
    }
    assert "version" not in entry


async def test_a_traced_understanding_shows_what_each_stage_got_and_produced() -> None:
    models = ScriptedModels(REMINDER_PLAN, entities=["class 5 blue"])
    index = RankedIndex(["fee.reminder.send", "dashboard.main.read"])

    with trace.collect() as collected:
        await pipeline(models, index).understand(SENTENCE, allowed=list(CATALOG), session_id="s-1")

    stages = {stage.key: stage for stage in collected.stages}
    assert list(stages) == [
        "decompose", "decompose_check", "embed", "search", "fuse", "plan", "validate",
    ]  # fmt: skip
    assert stages["decompose"].input["prompt"] == f"Message:\n{SENTENCE}"
    assert stages["decompose"].output == {
        "intents": [{"text": INTENT, "entities": ["class 5 blue"]}]
    }
    assert stages["decompose_check"].output["intents"] == [INTENT]
    assert stages["search"].output[0]["by_words"] == ["fee.reminder.send", "dashboard.main.read"]
    assert [c["capability"] for c in stages["fuse"].output["candidates_for_the_planner"]] == [
        "fee.reminder.send", "fee.overdue.list", "dashboard.main.read",
    ]  # fmt: skip
    assert stages["plan"].output == REMINDER_PLAN
    assert stages["validate"].output["outcome"] == "plan"
    assert all(stage.status == "ok" for stage in collected.stages)


async def test_output_the_validator_rejects_is_a_failed_stage_with_the_broken_rules() -> None:
    bad = {"outcome": "plan", "steps": [{"capability_id": "fee.invented", "params": []}]}
    models = ScriptedModels(bad)

    with trace.collect() as collected, pytest.raises(InvalidModelOutputError):
        await pipeline(models, RankedIndex(["fee.reminder.send"])).understand(
            SENTENCE, allowed=list(CATALOG), session_id="s-1"
        )

    validate = collected.stages[-1]
    assert validate.key == "validate" and validate.status == "failed"
    assert validate.note is not None and "UNKNOWN_CAPABILITY" in validate.note
