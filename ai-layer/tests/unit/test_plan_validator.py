"""The validator catches whatever the planner model gets wrong, before anything uses it."""

from __future__ import annotations

from typing import Any

import pytest

from app.capabilities.snapshot import load_snapshot
from app.planning.outcomes import NeedsInput, PlannedSteps, Refusal, RefusalReason
from app.validation.plan_validator import validate_plan
from app.validation.problems import InvalidModelOutputError

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
EVERYTHING = list(CATALOG)
SENTENCE = (
    "Ahmed Raza ki September ki fees 5,000 cash aaj mili hai, phir class 5 blue ka baqaya dikhao"
)


def payment(**overrides: Any) -> dict[str, Any]:
    params = {
        "invoice_id": {"lookup": {"student_name": "Ahmed Raza", "month": "September"}},
        "route": {"value": "cash"},
        "amount_received": {"value": 5000},
        "payment_date": {"value": "2026-09-14"},
        **overrides,
    }
    return {
        "capability_id": "fee.payment.record",
        "params": [{"name": name, **form} for name, form in params.items() if form is not None],
    }


OVERDUE = {
    "capability_id": "fee.overdue.list",
    "params": [
        {"name": "scope", "value": "section"},
        {"name": "section_id", "lookup": {"class": "class 5", "section": "blue"}},
    ],
}


def validate(output: dict[str, Any], **overrides: Any) -> Any:
    arguments: dict[str, Any] = {
        "sentence": SENTENCE,
        "catalog": CATALOG,
        "allowed": EVERYTHING,
        "candidates": EVERYTHING,
        "max_steps": 3,
        "session_id": "session-1",
        "plan_id": "plan-1",
        **overrides,
    }
    return validate_plan(output, **arguments)


def codes(output: dict[str, Any], **overrides: Any) -> list[str]:
    with pytest.raises(InvalidModelOutputError) as raised:
        validate(output, **overrides)
    return raised.value.codes


def test_a_good_two_step_plan_becomes_the_gateway_plan_with_versions_from_the_metadata() -> None:
    outcome = validate({"outcome": "plan", "steps": [payment(), OVERDUE]})

    assert isinstance(outcome, PlannedSteps)
    plan = outcome.plan
    assert (plan.plan_id, plan.session_id) == ("plan-1", "session-1")
    assert [(s.step, s.capability_id) for s in plan.steps] == [
        (1, "fee.payment.record"),
        (2, "fee.overdue.list"),
    ]
    assert plan.steps[0].capability_version == CATALOG["fee.payment.record"].version
    invoice = plan.steps[0].params["invoice_id"]
    assert invoice.lookup == {"student_name": "Ahmed Raza", "month": "September"}
    assert invoice.raw == "Ahmed Raza September"  # the words the chat shows and the audit keeps
    assert plan.steps[0].params["amount_received"].value == 5000
    assert plan.steps[1].params["section_id"].lookup == {"class": "class 5", "section": "blue"}


def test_a_hallucinated_capability_id_is_caught() -> None:
    invented = {**OVERDUE, "capability_id": "fee.defaulters.expel"}

    assert codes({"outcome": "plan", "steps": [invented]}) == ["UNKNOWN_CAPABILITY"]


def test_a_real_capability_outside_the_allow_list_or_the_candidates_is_caught() -> None:
    plan = {"outcome": "plan", "steps": [OVERDUE]}

    assert codes(plan, allowed=["fee.reminder.send"]) == ["NOT_ALLOWED"]
    assert codes(plan, candidates=["fee.reminder.send"]) == ["NOT_A_CANDIDATE"]


def test_invented_duplicate_and_missing_parameters_are_caught() -> None:
    step = payment(receipt_number={"value": "RCT-1"}, amount_received=None)
    step["params"].append({"name": "route", "value": "bank_challan"})

    assert sorted(codes({"outcome": "plan", "steps": [step]})) == [
        "DUPLICATE_PARAMETER",
        "INVENTED_PARAMETER",
        "MISSING_PARAMETER",
    ]


def test_a_parameter_with_a_default_may_be_left_out() -> None:
    reminder = {
        "capability_id": "fee.reminder.send",
        "params": [{"name": "section_id", "lookup": {"class": "class 5", "section": "blue"}}],
    }

    outcome = validate({"outcome": "plan", "steps": [reminder]})

    assert isinstance(outcome, PlannedSteps)
    assert set(outcome.plan.steps[0].params) == {"section_id"}


@pytest.mark.parametrize(
    ("override", "code"),
    [
        ({"invoice_id": {"value": 31}}, "WRONG_FORM"),
        ({"invoice_id": {"lookup": {"student_name": "Ahmed Raza Khan"}}}, "WORDS_NOT_IN_SENTENCE"),
        ({"invoice_id": {"words": "Ahmed Raza ki September ki fees"}}, "WRONG_FORM"),
        ({"invoice_id": {"lookup": {"student": "Ahmed Raza"}}}, "UNKNOWN_LOOKUP_PART"),
        ({"invoice_id": {"lookup": {"month": "September"}}}, "LOOKUP_NAMES_NOTHING"),
        ({"invoice_id": {"lookup": {"student_name": " "}}}, "MALFORMED_PARAMETER"),
        ({"route": {"words": "cash"}}, "WRONG_FORM"),
        ({"route": {"value": "cheque"}}, "NOT_AN_ALLOWED_VALUE"),
        ({"amount_received": {"value": 9000}}, "VALUE_NOT_IN_SENTENCE"),
        ({"amount_received": {"value": "five thousand"}}, "WRONG_TYPE"),
        ({"payment_date": {"value": "14/09/2026"}}, "WRONG_TYPE"),
        ({"payment_date": {"value": "2026-02-30"}}, "WRONG_TYPE"),
        ({"route": {"value": "cash", "words": "cash"}}, "MALFORMED_PARAMETER"),
        ({"route": {}}, "MALFORMED_PARAMETER"),
    ],
)
def test_parameter_forms_types_and_quotes_are_checked(override: dict[str, Any], code: str) -> None:
    assert codes({"outcome": "plan", "steps": [payment(**override)]}) == [code]


def test_a_lookup_phrase_may_rearrange_the_users_words_but_never_add_a_name() -> None:
    sentence = "Zain left in July but still got a September bill, get rid of it"
    cancel: dict[str, Any] = {
        "capability_id": "fee.cancellation.raise",
        "params": [
            {"name": "invoice_id", "lookup": {"student_name": "Zain", "month": "September"}},
            {"name": "reason", "value": "student_had_left"},
            {"name": "description", "value": "Zain left in July but was billed for September."},
        ],
    }

    outcome = validate(
        {"outcome": "plan", "steps": [cancel]}, sentence=sentence, record_words=["bill"]
    )
    assert isinstance(outcome, PlannedSteps)

    invented = {
        **cancel,
        "params": [
            {"name": "invoice_id", "lookup": {"student_name": "Zain Ahmed", "month": "September"}},
            *cancel["params"][1:],
        ],
    }
    assert codes({"outcome": "plan", "steps": [invented]}, sentence=sentence) == [
        "WORDS_NOT_IN_SENTENCE"
    ]
    only_connectors = {
        **cancel,
        "params": [
            {"name": "invoice_id", "lookup": {"student_name": "the invoice"}},
            *cancel["params"][1:],
        ],
    }
    assert codes(
        {"outcome": "plan", "steps": [only_connectors]}, sentence=sentence, record_words=["invoice"]
    ) == ["WORDS_NOT_IN_SENTENCE"]


def test_amounts_match_however_the_user_wrote_them() -> None:
    for written in (5000, 5000.0, "5000", "5000.00"):
        outcome = validate(
            {"outcome": "plan", "steps": [payment(amount_received={"value": written})]}
        )
        assert isinstance(outcome, PlannedSteps)


def test_a_value_from_an_earlier_step_must_name_a_fact_that_step_publishes() -> None:
    credit: dict[str, Any] = {
        "capability_id": "fee.credit.raise",
        "params": [
            {"name": "invoice_id", "from_step": 1, "field": "receipt_number"},
            {"name": "amount", "value": "5000"},
            {"name": "reason", "value": "duplicate_charge"},
            {"name": "description", "value": "Charged twice for September and paid both times."},
        ],
    }
    assert isinstance(validate({"outcome": "plan", "steps": [payment(), credit]}), PlannedSteps)

    wrong_field = {
        **credit,
        "params": [{**credit["params"][0], "field": "invoice_id"}, *credit["params"][1:]],
    }
    assert codes({"outcome": "plan", "steps": [payment(), wrong_field]}) == ["UNKNOWN_FIELD"]

    forward = {
        **credit,
        "params": [{**credit["params"][0], "from_step": 2}, *credit["params"][1:]],
    }
    assert codes({"outcome": "plan", "steps": [payment(), forward]}) == ["BAD_STEP_REFERENCE"]


def test_more_steps_than_allowed_are_caught() -> None:
    assert codes({"outcome": "plan", "steps": [OVERDUE] * 4}) == ["TOO_MANY_STEPS"]
    assert codes({"outcome": "plan", "steps": [OVERDUE] * 2}, max_steps=1) == ["TOO_MANY_STEPS"]


def test_a_refusal_and_a_request_for_input_come_through() -> None:
    refusal = validate({"outcome": "refusal", "refusal": "no_matching_capability"})
    assert refusal == Refusal(RefusalReason.NO_MATCHING_CAPABILITY)

    partial = payment(amount_received=None)
    needs = validate(
        {
            "outcome": "needs_input",
            "needs_input": {**partial, "missing": ["amount_received"]},
        }
    )
    assert isinstance(needs, NeedsInput)
    assert needs.capability_id == "fee.payment.record"
    assert needs.missing == ("amount_received",)
    assert set(needs.step.params) == {"invoice_id", "route", "payment_date"}
    assert needs.step.capability_version == CATALOG["fee.payment.record"].version


def test_a_request_for_input_must_name_real_required_parameters() -> None:
    asks = {"capability_id": "fee.reminder.send", "params": [], "missing": ["channel", "text"]}
    assert codes({"outcome": "needs_input", "needs_input": asks}) == ["NOT_A_MISSING_PARAMETER"]

    given_and_missing = {**payment(), "missing": ["route"]}
    assert codes({"outcome": "needs_input", "needs_input": given_and_missing}) == [
        "MISSING_BUT_GIVEN"
    ]

    also_incomplete = {**payment(route=None, amount_received=None), "missing": ["route"]}
    assert codes({"outcome": "needs_input", "needs_input": also_incomplete}) == [
        "MISSING_PARAMETER"
    ]


@pytest.mark.parametrize(
    "output",
    [
        {"outcome": "plan"},
        {"outcome": "refusal", "refusal": "no_matching_capability", "steps": [OVERDUE]},
        {"outcome": "needs_input", "refusal": "not_a_request"},
    ],
)
def test_an_answer_that_contradicts_its_own_outcome_is_caught(output: dict[str, Any]) -> None:
    assert codes(output) == ["INCONSISTENT_OUTCOME"]


@pytest.mark.parametrize(
    "output",
    [
        {"outcome": "guess"},
        {"outcome": "refusal", "refusal": "i_would_rather_not"},
        {"outcome": "plan", "steps": [{**OVERDUE, "capability_version": "abc"}]},
        {"outcome": "plan", "steps": [OVERDUE], "confidence": 0.9},
        {"outcome": "plan", "steps": [{**OVERDUE, "params": [{"name": "scope", "value": ["a"]}]}]},
    ],
)
def test_anything_outside_the_declared_shape_is_caught(output: dict[str, Any]) -> None:
    assert set(codes(output)) == {"SCHEMA"}
