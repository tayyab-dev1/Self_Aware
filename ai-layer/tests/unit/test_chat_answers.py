from __future__ import annotations

from datetime import date

import pytest

from app.capabilities.snapshot import load_snapshot
from app.gateway.models import EntityCandidate, ParamMetadata
from app.orchestration.answers import choose, is_no, is_yes, read_value
from app.orchestration.plan_cache import PlanCache, plan_cache_key
from app.planning.outcomes import Refusal, RefusalReason

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
TODAY = date(2026, 9, 14)


def param(capability_id: str, name: str) -> ParamMetadata:
    return next(p for p in CATALOG[capability_id].params if p.name == name)


@pytest.mark.parametrize("text", ["yes", "Haan", "ji haan", "OK.", "theek hai", "kar do"])
def test_yes_in_english_or_roman_urdu(text: str) -> None:
    assert is_yes(text) and not is_no(text)


@pytest.mark.parametrize("text", ["no", "Nahi", "cancel", "rehne do", "mat karo!"])
def test_no_in_english_or_roman_urdu(text: str) -> None:
    assert is_no(text) and not is_yes(text)


def test_a_sentence_is_neither_yes_nor_no() -> None:
    assert not is_yes("yes send it to class 5 green instead")
    assert not is_no("nahi, class 5 green ko bhejo")


OPTIONS = [
    EntityCandidate(id="2", label="Class 5 Blue", context="12 students"),
    EntityCandidate(id="3", label="Class 5 Green", context="10 students"),
]


@pytest.mark.parametrize(
    ("text", "chosen"),
    [("1", "2"), ("2", "3"), ("class 5 green", "3"), ("Blue", "2"), ("3", None), ("class 5", None)],
)
def test_an_option_is_chosen_by_number_or_by_name(text: str, chosen: str | None) -> None:
    assert choose(text, OPTIONS) == chosen


@pytest.mark.parametrize(
    ("text", "value"),
    [
        ("5000", "5000"),
        ("PKR 5,000", "5000"),
        ("5000.50 rupees", "5000.5"),
        ("five", None),
        ("5 or 6", None),
    ],
)
def test_an_amount_is_read_as_one_number(text: str, value: str | None) -> None:
    assert read_value(param("fee.payment.record", "amount_received"), text, TODAY) == value


@pytest.mark.parametrize(
    ("text", "value"),
    [
        ("today", "2026-09-14"),
        ("aaj", "2026-09-14"),
        ("yesterday", "2026-09-13"),
        ("2026-09-10", "2026-09-10"),
        ("10/09/2026", "2026-09-10"),
        ("kal", None),
        ("31/02/2026", None),
    ],
)
def test_a_date_is_read_only_when_it_is_certain(text: str, value: str | None) -> None:
    assert read_value(param("fee.payment.record", "payment_date"), text, TODAY) == value


@pytest.mark.parametrize(
    ("text", "value"),
    [("cash", "cash"), ("bank challan se", "bank_challan"), ("cheque", None)],
)
def test_an_allowed_value_is_matched_by_its_words(text: str, value: str | None) -> None:
    assert read_value(param("fee.payment.record", "route"), text, TODAY) == value


def test_free_text_is_taken_as_written() -> None:
    assert (
        read_value(param("fee.payment.record", "remarks"), " paid by uncle ", TODAY)
        == "paid by uncle"
    )


# --- the plan cache ---------------------------------------------------------------------------


def test_the_cache_key_ignores_case_spacing_and_end_punctuation_but_not_what_changes_a_plan() -> (
    None
):
    key = plan_cache_key("Class 5 Blue ka baqaya dikhao", ["a", "b"], CATALOG, TODAY)

    assert key == plan_cache_key("class 5 blue ka  baqaya dikhao?", ["b", "a"], CATALOG, TODAY)
    assert key != plan_cache_key("class 5 green ka baqaya dikhao", ["a", "b"], CATALOG, TODAY)
    assert key != plan_cache_key("Class 5 Blue ka baqaya dikhao", ["a"], CATALOG, TODAY)
    assert key != plan_cache_key(
        "Class 5 Blue ka baqaya dikhao", ["a", "b"], CATALOG, date(2026, 9, 15)
    )
    changed = {
        **CATALOG,
        "fee.overdue.list": CATALOG["fee.overdue.list"].model_copy(update={"version": "v2"}),
    }
    assert key != plan_cache_key("Class 5 Blue ka baqaya dikhao", ["a", "b"], changed, TODAY)


def test_the_cache_forgets_the_least_recently_used_plan_and_size_zero_keeps_nothing() -> None:
    refusal = Refusal(RefusalReason.NOT_A_REQUEST)
    cache = PlanCache(2)
    cache.put("a", refusal)
    cache.put("b", refusal)
    assert cache.get("a") is refusal
    cache.put("c", refusal)

    assert cache.get("b") is None and cache.get("a") is refusal and len(cache) == 2

    off = PlanCache(0)
    off.put("a", refusal)
    assert off.get("a") is None
