"""Building a plan from the pieces decompose read, instead of asking the planner (decision 79)."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.capabilities.snapshot import load_snapshot
from app.choosing.jev import DecisionRequest
from app.filling.assembler import assemble
from app.filling.pieces import vocabulary
from app.filling.values import SURE_AT, ValueChooser, wanted

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
TODAY = date(2026, 9, 28)
PAYMENT = {
    "student_name": "Hassan Ali",
    "class": "Class 5",
    "section": "Blue",
    "amount": "2,000",
    "payment_method": "cash",
    "date": "today",
}


def test_the_pieces_are_the_ones_the_published_capabilities_use() -> None:
    pieces = vocabulary(CATALOG.values())

    # Named by the backend: a parameter's filledBy, or the parts of the record it looks up.
    assert pieces["amount"].startswith("How much to credit")
    assert pieces["student_name"].startswith("the student the invoice is for")
    assert "payment_method" in pieces and "message_channel" in pieces
    assert "invoice_id" not in pieces  # an id is never something a user says


def test_a_payment_is_built_from_its_pieces_with_no_planner_call() -> None:
    built = assemble(CATALOG["fee.payment.record"], PAYMENT, TODAY)

    assert built == {
        "outcome": "plan",
        "steps": [
            {
                "capability_id": "fee.payment.record",
                "params": [
                    {
                        "name": "invoice_id",
                        "lookup": {
                            "student_name": "Hassan Ali",
                            "class": "Class 5",
                            "section": "Blue",
                        },
                    },
                    {"name": "route", "value": "cash"},
                    {"name": "amount_received", "value": 2000},
                    {"name": "payment_date", "value": "2026-09-28"},
                ],
            }
        ],
    }


def test_what_the_user_left_out_is_asked_for() -> None:
    built = assemble(CATALOG["fee.payment.record"], {"student_name": "Ahmed Raza"}, TODAY)

    assert built is not None
    assert built["outcome"] == "needs_input"
    assert built["needs_input"]["missing"] == ["amount_received", "payment_date", "route"]


def test_parts_that_name_no_record_travel_as_words() -> None:
    built = assemble(CATALOG["fee.reminder.send"], {"class": "Grade 4"}, TODAY)

    assert built is not None
    assert built["steps"][0]["params"][0] == {"name": "section_id", "words": "Grade 4"}


def test_anything_it_cannot_fill_safely_asks_the_planner_instead() -> None:
    # A word that is not one of the allowed values, with no answer from Jev.
    assert (
        assemble(CATALOG["fee.payment.record"], {**PAYMENT, "payment_method": "naqad"}, TODAY)
        is None
    )
    # A day it cannot work out.
    assert (
        assemble(CATALOG["fee.payment.record"], {**PAYMENT, "date": "last Friday"}, TODAY) is None
    )
    # An amount that is not a number.
    assert (
        assemble(CATALOG["fee.payment.record"], {**PAYMENT, "amount": "five thousand"}, TODAY)
        is None
    )
    # Two records read from the same words: a class and a section both from "class 5".
    both = {"list_scope": "section", "class": "class 5", "section": "blue"}
    assert assemble(CATALOG["fee.overdue.list"], both, TODAY) is None


def test_a_value_jev_read_from_the_users_word_is_used() -> None:
    built = assemble(
        CATALOG["fee.payment.record"],
        {**PAYMENT, "payment_method": "naqad"},
        TODAY,
        {"route": "cash"},
    )

    assert built is not None
    assert {"name": "route", "value": "cash"} in built["steps"][0]["params"]


def test_only_the_words_that_are_not_already_a_value_are_asked_about() -> None:
    asking = wanted(CATALOG["fee.payment.record"], {**PAYMENT, "payment_method": "naqad"})
    assert [param.name for param, _ in asking] == ["route"]

    assert wanted(CATALOG["fee.payment.record"], PAYMENT) == []  # "cash" is already a value


class Jev:
    def __init__(self, answer: dict[str, Any]) -> None:
        self.answer = answer
        self.asked: list[DecisionRequest] = []

    async def decide(self, request: DecisionRequest) -> dict[str, Any]:
        self.asked.append(request)
        return self.answer


def answer(value: str, confidence: float) -> dict[str, Any]:
    return {
        "answers": {
            "route": {
                "type": "choice",
                "choice": value,
                "confidence": confidence,
                "probabilities": {value: confidence},
            }
        }
    }


async def test_jev_reads_the_users_word_as_one_of_the_allowed_values() -> None:
    jev = Jev(answer("cash", 0.98))

    chosen = await ValueChooser(jev).choose(
        CATALOG["fee.payment.record"], {**PAYMENT, "payment_method": "naqad"}
    )

    assert chosen == {"route": "cash"}
    [asked] = jev.asked
    assert set(asked.questions["route"]["criteria"]) == {"cash", "bank_challan", "none"}
    assert '"naqad"' in asked.questions["route"]["instructions"]


async def test_a_value_jev_is_unsure_of_is_left_for_the_planner() -> None:
    chosen = await ValueChooser(Jev(answer("cash", SURE_AT - 0.1))).choose(
        CATALOG["fee.payment.record"], {**PAYMENT, "payment_method": "naqad"}
    )

    assert chosen == {}
