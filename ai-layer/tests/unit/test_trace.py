"""The per-turn trace: what is kept, in what order, and when nothing is kept."""

from __future__ import annotations

import pytest

from app.core import trace


def test_stages_are_kept_in_the_order_they_start_with_their_input_and_output() -> None:
    with (
        trace.collect() as collected,
        trace.stage("outer", "Outer", trace.BY_CODE, "Does two things.") as outer,
    ):
        outer.input = {"sentence": "class 5"}
        trace.note("inner", "Inner", trace.BY_CODE, "A decision.", output={"hit": False})
        outer.output = ["done"]

    stages = collected.as_json()
    assert [stage["key"] for stage in stages] == ["outer", "inner"]
    assert stages[0]["input"] == {"sentence": "class 5"}
    assert stages[0]["output"] == ["done"]
    assert stages[0]["duration_ms"] >= 0
    assert stages[1]["output"] == {"hit": False} and stages[1]["duration_ms"] is None


def test_a_stage_that_raises_is_marked_failed_and_the_error_still_raises() -> None:
    with (
        trace.collect() as collected,
        pytest.raises(ValueError, match="bad answer"),
        trace.stage("plan", "Plan", trace.by_model("m"), "Plans."),
    ):
        raise ValueError("bad answer")

    (stage,) = collected.as_json()
    assert stage["status"] == "failed"
    assert stage["note"] == "ValueError: bad answer"


def test_outside_collect_nothing_is_kept() -> None:
    assert not trace.active()
    with trace.stage("plan", "Plan", trace.BY_CODE, "Plans.") as stage:
        stage.output = {"x": 1}
    with trace.collect() as collected:
        assert trace.active()
    assert collected.stages == []
    assert not trace.active()


def test_long_text_is_cut_and_anything_is_made_json() -> None:
    with trace.collect() as collected:
        trace.note(
            "x", "X", trace.BY_CODE, "Keeps.", input="a" * (trace.MAX_TEXT + 5), output={1, 2}
        )

    (stage,) = collected.as_json()
    assert stage["input"].endswith("(5 more characters)")
    assert sorted(stage["output"]) == [1, 2]
