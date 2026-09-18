"""The retrieval eval set itself: sound enough to measure with (no Docker, no model needed)."""

from __future__ import annotations

from app.capabilities.snapshot import load_snapshot
from eval.retrieval.dataset import (
    EvalSentence,
    cluster_members,
    dataset_problems,
    load_distractors,
    load_sentences,
)
from eval.retrieval.metrics import Outcome, cluster_check, score

CAPABILITIES = load_snapshot().capabilities


def test_the_committed_eval_set_is_sound() -> None:
    sentences = load_sentences()

    assert dataset_problems(sentences, CAPABILITIES, load_distractors()) == []
    assert {s.source for s in sentences} == {"contract", "authored"}
    assert cluster_members(CAPABILITIES) == {
        "fee.cancellation.raise",
        "fee.credit.raise",
        "fee.latefee.waive",
        "fee.writeoff.propose",
        "fee.overdue.list",
        "fee.reminder.send",
    }


def sentence(text: str, expected: str, lang: str = "en", gloss: str | None = None) -> EvalSentence:
    return EvalSentence.model_validate(
        {"text": text, "lang": lang, "expected": expected, "source": "authored", "gloss": gloss}
    )


def test_a_thin_or_mislabelled_set_is_called_out() -> None:
    sentences = [
        sentence("remind them", "fee.reminder.send"),
        sentence("Remind  them", "fee.reminder.send"),
        sentence("do something", "fee.teleport"),
        sentence("jurmana معاف karo", "fee.latefee.waive", lang="ur-Latn", gloss="waive the fine"),
        sentence("baqaya dikhao", "fee.overdue.list", lang="ur-Latn"),
    ]

    problems = "\n".join(dataset_problems(sentences, CAPABILITIES))

    assert "5 sentences, expected 150-200" in problems
    assert "not published capabilities: ['fee.teleport']" in problems
    assert "listed twice: ['remind them']" in problems
    assert "non-Latin letters" in problems
    assert "without an English gloss: ['baqaya dikhao']" in problems
    assert "capabilities no sentence targets" in problems
    assert "fee.credit.raise (0)" in problems


def outcome(expected: str, candidates: tuple[str, ...], fused_rank: int | None) -> Outcome:
    return Outcome(
        sentence=sentence("q", expected),
        query="q",
        candidates=candidates,
        fused_rank=fused_rank,
        top=candidates[:3],
    )


def test_recall_uses_the_candidates_and_ranks_use_the_fused_order() -> None:
    scores = score(
        [
            outcome("a", ("b", "a"), fused_rank=4),
            outcome("a", ("a",), fused_rank=1),
            outcome("a", ("b",), fused_rank=None),
        ]
    )

    assert scores.recall_at_cap == 2 / 3
    assert scores.recall_at[1] == 1 / 3
    assert scores.recall_at[5] == 2 / 3
    assert scores.mrr == (1 / 4 + 1) / 3


def test_a_cluster_offered_in_part_is_reported_broken() -> None:
    siblings = {"x": ("y",), "y": ("x",), "z": ()}

    check = cluster_check(
        [outcome("x", ("x", "y"), 1), outcome("x", ("x", "z"), 1), outcome("z", ("z",), 1)],
        siblings,
    )

    assert (check.touched, check.whole) == (2, 1)
    assert check.broken[0].candidates == ("x", "z")
