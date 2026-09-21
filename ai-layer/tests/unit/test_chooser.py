"""The capability chooser: what Jev is asked, how its answer is checked, and the shortlist."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import pytest

from app.capabilities.snapshot import load_snapshot
from app.choosing.chooser import (
    NONE,
    CapabilityChooser,
    IntentChoice,
    decision_request,
    option,
    parse_choice,
)
from app.choosing.jev import DecisionRequest
from app.llm.runner import ModelOutputError

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
CANDIDATES = [CATALOG["fee.overdue.list"], CATALOG["fee.reminder.send"]]
OPTIONS = ["fee.overdue.list", "fee.reminder.send", NONE]


def answer(*per_intent: Mapping[str, float]) -> dict[str, Any]:
    return {
        "answers": {
            f"intent_{n}": {
                "type": "choice",
                "choice": max(probabilities, key=lambda c: probabilities[c]),
                "confidence": max(probabilities.values()),
                "probabilities": dict(probabilities),
            }
            for n, probabilities in enumerate(per_intent, start=1)
        }
    }


def intent(probabilities: Mapping[str, float]) -> IntentChoice:
    return IntentChoice("x", max(probabilities, key=lambda c: probabilities[c]), 0.9, probabilities)


def test_an_option_says_what_the_capability_does_and_needs_with_what_records_are_found_by() -> None:
    described = option(CATALOG["fee.reminder.send"])

    assert described["does"] == CATALOG["fee.reminder.send"].description
    assert described["kind"] == "changes records"
    assert described["needs"]["section_id"].startswith("The section whose families")
    assert "found by the class and section together" in described["needs"]["section_id"]
    assert "one of whatsapp, sms, email" in described["needs"]["channel"]
    assert option(CATALOG["dashboard.main.read"])["needs"] == "nothing"


def test_each_intent_is_one_choice_among_the_candidates_and_none() -> None:
    request = decision_request(["Show the defaulters", "Remind them"], CANDIDATES)

    assert request.model == "jev-1.13.0"
    assert request.state == {"requests": ["Show the defaulters", "Remind them"]}
    assert list(request.questions) == ["intent_1", "intent_2"]
    second = request.questions["intent_2"]
    assert second["type"] == "choice"
    assert '"Remind them"' in second["instructions"]
    assert list(second["criteria"]) == OPTIONS


def test_a_clear_pick_leaves_one_candidate() -> None:
    assert intent({"fee.overdue.list": 0.96, "fee.reminder.send": 0.02, NONE: 0.02}).kept() == [
        "fee.overdue.list"
    ]


def test_a_close_call_keeps_the_candidates_that_are_close() -> None:
    close = intent({"fee.overdue.list": 0.55, "fee.reminder.send": 0.40, NONE: 0.05})

    assert close.kept() == ["fee.overdue.list", "fee.reminder.send"]


def test_none_winning_keeps_nothing() -> None:
    assert intent({"fee.overdue.list": 0.3, "fee.reminder.send": 0.1, NONE: 0.6}).kept() == []


def test_the_shortlist_follows_the_intents_without_repeats() -> None:
    choice = parse_choice(
        answer(
            {"fee.overdue.list": 0.9, "fee.reminder.send": 0.05, NONE: 0.05},
            {"fee.overdue.list": 0.04, "fee.reminder.send": 0.95, NONE: 0.01},
        ),
        ["Show the defaulters", "Remind them"],
        OPTIONS,
    )

    assert choice.shortlist == ("fee.overdue.list", "fee.reminder.send")


@pytest.mark.parametrize(
    "broken",
    [
        {"answers": {}},
        {"answers": {"intent_1": {"type": "choice", "choice": "fee.invented", "confidence": 1.0,
                                  "probabilities": {"fee.invented": 1.0}}}},
        {"answers": {"intent_1": {"type": "noul", "noul": 0.9}}},
        {"model": "jev-1.13.0"},
    ],
)  # fmt: skip
def test_an_answer_that_breaks_a_rule_is_an_output_error(broken: dict[str, Any]) -> None:
    with pytest.raises(ModelOutputError):
        parse_choice(broken, ["Show the defaulters"], OPTIONS)


async def test_the_chooser_asks_once_for_every_intent() -> None:
    asked: list[DecisionRequest] = []

    class Jev:
        async def decide(self, request: DecisionRequest) -> dict[str, Any]:
            asked.append(request)
            return answer({"fee.overdue.list": 0.97, "fee.reminder.send": 0.02, NONE: 0.01})

    choice = await CapabilityChooser(Jev()).choose(["Show the defaulters"], CANDIDATES)

    assert len(asked) == 1
    assert choice.shortlist == ("fee.overdue.list",)
