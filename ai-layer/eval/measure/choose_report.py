"""The chooser experiment's report: the four numbers with and without Jev, and Jev on its own.

Written to eval/reports/measure_with_jev.md by ``make measure-jev``.
"""

from __future__ import annotations

import statistics
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path

from app.choosing.chooser import CHOOSER_MODEL, KEEP_SHARE, MAX_KEPT, NONE, NONE_WINS_AT
from app.llm.runner import PLANNER_MODEL
from eval.measure.pipeline import CaseResult
from eval.measure.report import LABELS, NAMES
from eval.measure.scores import GROUPS, FourNumbers

REPORT_PATH = Path(__file__).resolve().parents[1] / "reports" / "measure_with_jev.md"
CONFIDENCE_BUCKETS = ((0.9, 1.01), (0.7, 0.9), (0.5, 0.7), (0.0, 0.5))


def comparison_table(before: Mapping[str, FourNumbers], after: Mapping[str, FourNumbers]) -> str:
    """Each number without the chooser, with it, and the change, per language."""
    lines = [f"{'':<22}" + "".join(f"{LABELS[g]:<26}" for g in GROUPS)]
    for key, name in NAMES.items():
        cells = []
        for group in GROUPS:
            old, new = getattr(before[group], key), getattr(after[group], key)
            change = (new - old) * 100
            cells.append(f"{f'{old:.1%} -> {new:.1%} ({change:+.1f})':<26}")
        lines.append(f"{name:<22}" + "".join(cells).rstrip())
    return "\n".join(lines)


def chooser_right(result: CaseResult) -> bool | None:
    """Jev's own pick was right: the expected capability, or "none" for a sentence to refuse."""
    if result.choice is None:
        return None
    picks = {intent.choice for intent in result.choice.intents}
    if result.case.expected is None:
        return picks == {NONE}
    return result.case.expected in picks


def _share(part: int, whole: int) -> str:
    return f"{part / whole:.1%} ({part}/{whole})" if whole else "n/a"


def write_choose_report(
    before: Sequence[CaseResult],
    after: Sequence[CaseResult],
    numbers_before: Mapping[str, FourNumbers],
    numbers_after: Mapping[str, FourNumbers],
    seconds: Sequence[float],
    *,
    path: Path = REPORT_PATH,
) -> str:
    chosen = [r for r in after if r.choice is not None]
    labelled = [r for r in chosen if r.case.expected is not None]
    to_refuse = [r for r in chosen if r.case.expected is None]
    lines = [
        "# Measure: the capability chooser (Jev) before the planner",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make measure-jev`. "
        f"Chooser `{CHOOSER_MODEL}`, planner `{PLANNER_MODEL}`, the same decompose answers and "
        "retrieval as `measure.md`, answers from `eval/recordings/` (`choose.json`, "
        "`plan_after_choose.json`).",
        "",
        "Without the chooser the planner sees every candidate the POC can run; with it, only "
        f"Jev's shortlist: per intent, the candidates holding {KEEP_SHARE:.0%} of the probability "
        f'that is not "none" (at most {MAX_KEPT}), nothing when "none" holds '
        f"{NONE_WINS_AT:.0%} or more. An empty shortlist is a refusal with no planner call.",
        "",
        "## The four numbers: planner alone -> Jev, then the planner",
        "",
        "```",
        comparison_table(numbers_before, numbers_after),
        "```",
        "",
        "## Jev on its own",
        "",
        f"Over the {len(labelled)} labelled sentences and {len(to_refuse)} sentences to refuse "
        "that reached it (retrieval found at least one candidate the POC can run).",
        "",
        f"- **picked the expected capability**: "
        f"{_share(sum(bool(chooser_right(r)) for r in labelled), len(labelled))}",
        f"- **expected capability in the shortlist**: "
        f"{_share(sum(r.case.expected in r.plannable for r in labelled), len(labelled))}",
        f'- **"none" for a sentence to refuse**: '
        f"{_share(sum(bool(chooser_right(r)) for r in to_refuse), len(to_refuse))}",
        "- **shortlist sizes** (labelled): "
        + ", ".join(
            f"{size}: {sum(len(r.plannable) == size for r in labelled)}"
            for size in range(MAX_KEPT + 1)
        ),
    ]
    if seconds:
        ordered = sorted(seconds)
        lines.append(
            f"- **time per call** (when recorded, {len(ordered)} calls): median "
            f"{statistics.median(ordered):.2f} s, 90th percentile "
            f"{ordered[int(0.9 * (len(ordered) - 1))]:.2f} s, slowest {ordered[-1]:.2f} s"
        )
    lines += [
        "",
        "### Is its confidence honest?",
        "",
        "Every sentence that reached Jev, by the confidence of its first intent's pick.",
        "",
        "| confidence | sentences | pick right |",
        "|---|---:|---:|",
    ]
    for low, high in CONFIDENCE_BUCKETS:
        bucket = [r for r in chosen if r.choice and low <= r.choice.intents[0].confidence < high]
        right = sum(bool(chooser_right(r)) for r in bucket)
        label = f"{low:.1f} to {min(high, 1.0):.1f}"
        lines.append(f"| {label} | {len(bucket)} | {_share(right, len(bucket))} |")

    lines += [
        "",
        "## Sentences whose result changed",
        "",
        "| sentence | lang | expected | planner alone | with Jev | Jev picked |",
        "|---|---|---|---|---|---|",
    ]
    by_text = {r.case.text: r for r in before}
    changed = 0
    for new in after:
        old = by_text[new.case.text]
        if _right(old) == _right(new):
            continue
        changed += 1
        picked = (
            "; ".join(f"{i.choice} {i.confidence:.2f}" for i in new.choice.intents)
            if new.choice
            else ""
        )
        lines.append(
            f"| {new.case.text} | {new.case.lang} | `{new.case.expected or 'refuse'}` | "
            f"{_verdict(old)} | {_verdict(new)} | {picked} |"
        )
    if not changed:
        lines.append("| (none) | | | | | |")
    body = "\n".join(lines) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
    return body


def _right(result: CaseResult) -> bool:
    if result.case.expected is None:
        return result.refusal_decision_correct
    return result.planned_correctly


def _verdict(result: CaseResult) -> str:
    mark = "right" if _right(result) else "wrong"
    what = ", ".join(result.capabilities) if result.acted else result.outcome
    detail = f" ({', '.join(result.problems)})" if result.problems and not result.acted else ""
    return f"{mark}: {what}{detail}"
