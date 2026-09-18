"""The Phase 7 measurement's own logic, with no Docker, embeddings or model."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest

from app.capabilities.snapshot import load_snapshot
from app.llm.runner import ModelOutputError, ModelRequest, ModelUnavailableError
from app.validation.plan_validator import validate_plan
from eval.measure.faults import FAULTS, inject_faults
from eval.measure.pipeline import Case, CaseResult, load_cases
from eval.measure.recordings import MissingRecordingError, ModelCallFailedError, Recordings
from eval.measure.scores import four_numbers

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
SYSTEM = "You plan."


def request(system: str = SYSTEM, *, thinking: bool = True) -> ModelRequest:
    return ModelRequest(
        model="m", system=system, prompt="p", schema={}, purpose="plan", thinking=thinking
    )


class Model:
    def __init__(self, answer: dict[str, Any] | Exception) -> None:
        self.answer = answer
        self.calls = 0

    async def generate(self, _: ModelRequest) -> dict[str, Any]:
        self.calls += 1
        if isinstance(self.answer, Exception):
            raise self.answer
        return self.answer


# --- recordings -------------------------------------------------------------------------------


async def test_recording_asks_once_and_replay_answers_without_a_model(tmp_path: Path) -> None:
    model = Model({"outcome": "refusal", "refusal": "not_a_request"})
    recorder = Recordings("plan", "m", SYSTEM, mode="record", directory=tmp_path)

    first = await recorder.model_for("hello", model).generate(request())
    again = await recorder.model_for("hello", model).generate(request())
    replayed = (
        await Recordings("plan", "m", SYSTEM, mode="replay", directory=tmp_path)
        .model_for("hello", None)
        .generate(request())
    )

    assert first == again == replayed == {"outcome": "refusal", "refusal": "not_a_request"}
    assert model.calls == 1
    stored = json.loads((tmp_path / "plan.json").read_text())
    assert stored["model"] == "m" and set(stored["answers"]) == {"hello"}


async def test_replay_refuses_a_missing_answer_or_another_prompt(tmp_path: Path) -> None:
    recorder = Recordings("plan", "m", SYSTEM, mode="record", directory=tmp_path)
    await recorder.model_for("hello", Model({"outcome": "plan"})).generate(request())

    replay = Recordings("plan", "m", SYSTEM, mode="replay", directory=tmp_path)
    with pytest.raises(MissingRecordingError, match="make measure"):
        await replay.model_for("something new", None).generate(request())
    with pytest.raises(MissingRecordingError, match="another model, prompt or thinking"):
        Recordings("plan", "m", "You plan differently.", mode="replay", directory=tmp_path)


async def test_a_changed_prompt_starts_a_fresh_recording(tmp_path: Path) -> None:
    await (
        Recordings("plan", "m", SYSTEM, mode="record", directory=tmp_path)
        .model_for("hello", Model({"outcome": "plan"}))
        .generate(request())
    )

    model = Model({"outcome": "refusal"})
    changed = Recordings("plan", "m", "New prompt.", mode="record", directory=tmp_path)
    await changed.model_for("hello", model).generate(request("New prompt."))

    assert model.calls == 1


async def test_a_recording_without_thinking_is_kept_apart_from_one_with_it(tmp_path: Path) -> None:
    await (
        Recordings("plan", "m", SYSTEM, mode="record", directory=tmp_path)
        .model_for("hello", Model({"outcome": "plan"}))
        .generate(request())
    )

    with pytest.raises(MissingRecordingError, match="thinking setting"):
        Recordings("plan", "m", SYSTEM, mode="replay", thinking=False, directory=tmp_path)

    model = Model({"outcome": "refusal"})
    fresh = Recordings("plan", "m", SYSTEM, mode="record", thinking=False, directory=tmp_path)
    await fresh.model_for("hello", model).generate(request(thinking=False))
    assert model.calls == 1
    assert json.loads((tmp_path / "plan.json").read_text())["thinking"] is False

    with pytest.raises(MissingRecordingError, match="another thinking setting"):
        await fresh.model_for("hello", None).generate(request(thinking=True))


async def test_a_bad_answer_is_recorded_but_an_outage_is_not(tmp_path: Path) -> None:
    recorder = Recordings("plan", "m", SYSTEM, mode="record", directory=tmp_path)

    with pytest.raises(ModelCallFailedError):
        await recorder.model_for("bad", Model(ModelOutputError("no structured output"))).generate(
            request()
        )
    with pytest.raises(ModelUnavailableError):
        await recorder.model_for("down", Model(ModelUnavailableError("timeout"))).generate(
            request()
        )

    replay = Recordings("plan", "m", SYSTEM, mode="replay", directory=tmp_path)
    with pytest.raises(ModelCallFailedError):
        await replay.model_for("bad", None).generate(request())
    with pytest.raises(MissingRecordingError):
        await replay.model_for("down", None).generate(request())


# --- faults -----------------------------------------------------------------------------------

SENTENCE = "Ahmed Raza ki September ki fees 5000 cash aaj mili hai, record karo"
GOOD_ANSWER = {
    "outcome": "plan",
    "steps": [
        {
            "capability_id": "fee.payment.record",
            "params": [
                {"name": "invoice_id", "words": "Ahmed Raza ki September ki fees"},
                {"name": "route", "value": "cash"},
                {"name": "amount_received", "value": 5000},
                {"name": "payment_date", "value": "2026-09-14"},
            ],
        }
    ],
}


def result(case: Case, outcome: str, **fields: Any) -> CaseResult:
    return CaseResult(case, ("intent",), tuple(CATALOG), tuple(CATALOG), outcome, **fields)  # type: ignore[arg-type]


def test_the_good_answer_is_valid_so_every_fault_starts_from_a_valid_plan() -> None:
    validate_plan(
        copy.deepcopy(GOOD_ANSWER), sentence=SENTENCE, catalog=CATALOG, allowed=list(CATALOG),
        candidates=list(CATALOG), max_steps=3, session_id="s",
    )  # fmt: skip


def test_every_fault_applies_to_a_full_answer_and_is_caught_for_its_own_reason() -> None:
    case = Case(SENTENCE, "ur-Latn", "fee.payment.record", "authored")
    planned = result(case, "plan", capabilities=("fee.payment.record",), raw_plan=GOOD_ANSWER)

    tallies = inject_faults([planned], CATALOG)

    assert set(tallies) == {"all", "ur-Latn"}
    assert set(tallies["all"]) == set(FAULTS)
    for name, tally in tallies["all"].items():
        assert (tally.applied, tally.caught, tally.right_reason) == (1, 1, 1), name


def test_faults_skip_what_does_not_apply_and_ignore_answers_that_were_not_plans() -> None:
    dashboard = {
        "outcome": "plan",
        "steps": [{"capability_id": "dashboard.main.read", "params": []}],
    }
    case = Case("dashboard dikhao", "ur-Latn", "dashboard.main.read", "authored")
    tallies = inject_faults(
        [
            result(case, "plan", capabilities=("dashboard.main.read",), raw_plan=dashboard),
            result(case, "refusal", raw_plan={"outcome": "refusal", "refusal": "not_a_request"}),
        ],
        CATALOG,
    )

    assert "amount the user never wrote" not in tallies["all"]
    assert "required parameter left out" not in tallies["all"]
    assert tallies["all"]["hallucinated capability id"].applied == 1


# --- scores -----------------------------------------------------------------------------------


def test_the_four_numbers_count_what_they_say() -> None:
    reminder = Case("remind 5 blue", "en", "fee.reminder.send", "authored")
    payment = Case("paisay mil gaye", "ur-Latn", "fee.payment.record", "authored")
    weather = Case("mausam?", "ur-Latn", None, "authored")
    results = [
        result(reminder, "plan", capabilities=("fee.reminder.send",), raw_plan=GOOD_ANSWER),
        CaseResult(payment, ("intent",), ("dashboard.main.read",), ("dashboard.main.read",), "plan",
                   capabilities=("dashboard.main.read",)),
        result(weather, "needs_input", capabilities=("fee.payment.record",)),
    ]  # fmt: skip

    numbers = four_numbers(results, inject_faults(results[:1], CATALOG))

    assert numbers["all"].recall_at_30 == 0.5  # the payment was not among its candidates
    assert numbers["all"].plan_accuracy == 0.5
    assert numbers["all"].refusal_correctness == pytest.approx(2 / 3)  # acted on the weather
    assert numbers["all"].validator_catch_rate == 1.0
    assert numbers["ur-Latn"].plan_accuracy == 0.0 and numbers["ur-Latn"].refusal_cases == 1
    assert numbers["en"].sentences == 1


def test_the_committed_cases_hold_labelled_sentences_and_sentences_to_refuse() -> None:
    cases = load_cases()
    refusals = [c for c in cases if c.expected is None]

    assert len(cases) - len(refusals) == 175
    assert len(refusals) == 70
    assert {c.lang for c in refusals} == {"en", "ur-Latn"}
    assert not {c.text.casefold() for c in refusals} & {
        c.text.casefold() for c in cases if c.expected is not None
    }
