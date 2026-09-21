"""Phase 5 "done when", against the real decompose and plan models through the Gemini API.

    make ai-model-checks      (needs AI_LAYER_GEMINI_API_KEY; a run makes about a dozen calls)

Retrieval is replaced by every published capability, so these check the two model calls and the
validator, not the index. Fails, rather than skips, when there is no API key: an unrun check is
not a passing check. Models are not deterministic, so each sentence is one a correct planner gets
right every time.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Collection, Sequence

import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.core.settings import Settings
from app.decompose.decomposer import Decomposer
from app.llm.gemini import GeminiModel
from app.planning.outcomes import PlannedSteps, Refusal
from app.planning.planner import Planner
from app.planning.service import SentencePlanner, Understanding
from app.retrieval.fusion import Candidate
from app.retrieval.hybrid import RetrievalResult
from app.validation.problems import normalise
from domain.school.calendar import SCHOOL_TIME_ZONE, school_today
from domain.school.glossary import RECORD_WORDS, glossary_lines

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}


class EveryCapability:
    async def retrieve(self, queries: Sequence[str], allowed: Collection[str]) -> RetrievalResult:
        ids = [capability_id for capability_id in CATALOG if capability_id in allowed]
        return RetrievalResult(tuple(Candidate(capability_id=c, score=0.0) for c in ids))


@pytest_asyncio.fixture(scope="module")
async def planner() -> AsyncIterator[SentencePlanner]:
    model = GeminiModel.from_settings(Settings())
    yield SentencePlanner(
        Decomposer(model, glossary_lines()),
        EveryCapability(),
        Planner(
            model,
            max_steps=3,
            # The real date, so the sentences' relative dates ("aaj") mean what they say.
            today=school_today,
            time_zone=SCHOOL_TIME_ZONE,
            record_words=RECORD_WORDS,
        ),
        lambda: CATALOG,
    )
    await model.aclose()


async def understand(planner: SentencePlanner, sentence: str) -> Understanding:
    return await planner.understand(sentence, allowed=list(CATALOG), session_id="model-check")


async def test_a_roman_urdu_sentence_produces_a_valid_plan_with_english_intents_and_names_intact(
    planner: SentencePlanner,
) -> None:
    sentence = "class 5 blue ke defaulters ko whatsapp par reminder bhejo"

    understanding = await understand(planner, sentence)

    outcome = understanding.outcome
    assert isinstance(outcome, PlannedSteps), outcome
    (step,) = outcome.plan.steps
    assert step.capability_id == "fee.reminder.send"
    assert step.capability_version == CATALOG["fee.reminder.send"].version
    assert normalise(step.params["section_id"].raw or "") == "class 5 blue"
    assert step.params.get("channel") is None or step.params["channel"].value == "whatsapp"
    (intent,) = understanding.decomposition.intents
    assert "5 blue" in intent.text.casefold()
    assert all(normalise(entity) in normalise(sentence) for entity in intent.entities)
    # English: the validator already refused Urdu function words; these are the sentence's own.
    assert not {"ke", "ko", "par", "bhejo"} & set(normalise(intent.text).split())


async def test_a_sentence_matching_nothing_is_refused_not_guessed(planner: SentencePlanner) -> None:
    understanding = await understand(planner, "kal Lahore mein mausam kaisa hoga?")

    assert isinstance(understanding.outcome, Refusal), understanding.outcome


async def test_a_two_part_sentence_produces_two_steps_in_order(planner: SentencePlanner) -> None:
    sentence = (
        "Ahmed Raza ki September ki fees 5000 cash aaj mili hai, record karo, "
        "phir class 5 blue ka baqaya dikhao"
    )

    understanding = await understand(planner, sentence)

    outcome = understanding.outcome
    assert isinstance(outcome, PlannedSteps), outcome
    assert [step.capability_id for step in outcome.plan.steps] == [
        "fee.payment.record",
        "fee.overdue.list",
    ]
    payment, overdue = outcome.plan.steps
    assert payment.params["amount_received"].value in (5000, "5000", 5000.0)
    assert payment.params["route"].value == "cash"
    assert payment.params["payment_date"].value == school_today().isoformat()  # "aaj"
    assert "ahmed raza" in normalise(payment.params["invoice_id"].raw or "")
    assert "class 5 blue" in normalise(overdue.params["section_id"].raw or "")
    assert len(understanding.decomposition.intents) == 2
