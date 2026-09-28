"""The pieces design's report: both ways side by side, and what the pieces could not fill."""

from __future__ import annotations

from collections import Counter
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path

from app.llm.runner import DECOMPOSE_MODEL, PLANNER_MODEL
from eval.measure.pipeline import CaseResult
from eval.measure.report import LABELS, NAMES
from eval.measure.scores import GROUPS, FourNumbers

REPORT_PATH = Path(__file__).resolve().parents[1] / "reports" / "measure_pieces.md"


def comparison_table(planner: Mapping[str, FourNumbers], pieces: Mapping[str, FourNumbers]) -> str:
    lines = [f"{'':<22}" + "".join(f"{LABELS[group]:<26}" for group in GROUPS)]
    for key, name in NAMES.items():
        cells = []
        for group in GROUPS:
            old, new = getattr(planner[group], key), getattr(pieces[group], key)
            cells.append(f"{f'{old:.1%} -> {new:.1%} ({(new - old) * 100:+.1f})':<26}")
        lines.append(f"{name:<22}" + "".join(cells).rstrip())
    return "\n".join(lines)


def _right(result: CaseResult) -> bool:
    if result.case.expected is None:
        return result.refusal_decision_correct
    return result.planned_correctly


def write_pieces_report(
    planner: Sequence[CaseResult],
    pieces: Sequence[CaseResult],
    numbers_planner: Mapping[str, FourNumbers],
    numbers_pieces: Mapping[str, FourNumbers],
    *,
    path: Path = REPORT_PATH,
) -> str:
    built = [result for result in pieces if result.from_pieces]
    fell_back = [result for result in pieces if not result.from_pieces]
    by_language = Counter(result.case.lang for result in built)
    lines = [
        "# Measure: planning from the pieces, instead of a second model call",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make measure-pieces`. "
        f"Decompose `{DECOMPOSE_MODEL}` (asked for the pieces), the chooser and the value readings "
        f"from Jev, the planner `{PLANNER_MODEL}` only where the pieces could not fill the plan.",
        "",
        "## Both ways, over the same cases",
        "",
        "```",
        comparison_table(numbers_planner, numbers_pieces),
        "```",
        "",
        f"- **Built without a planner call**: {len(built)} of {len(pieces)} "
        f"({len(built) / len(pieces):.1%}); "
        + ", ".join(f"{lang} {count}" for lang, count in sorted(by_language.items())),
        f"- **Asked the planner**: {len(fell_back)} of {len(pieces)}",
        "",
        "## What the pieces got wrong that the planner got right",
        "",
        "| sentence | lang | expected | planner | from the pieces |",
        "|---|---|---|---|---|",
    ]
    by_text = {result.case.text: result for result in planner}
    changed = 0
    for new in pieces:
        old = by_text[new.case.text]
        if _right(old) == _right(new):
            continue
        changed += 1
        lines.append(
            f"| {new.case.text} | {new.case.lang} | `{new.case.expected or 'refuse'}` | "
            f"{_verdict(old)} | {_verdict(new)}{'' if new.from_pieces else ' (planner asked)'} |"
        )
    if not changed:
        lines.append("| (none) | | | | |")
    body = "\n".join(lines) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
    return body


def _verdict(result: CaseResult) -> str:
    mark = "right" if _right(result) else "wrong"
    what = ", ".join(result.capabilities) if result.acted else result.outcome
    detail = f" ({', '.join(result.problems)})" if result.problems and not result.acted else ""
    return f"{mark}: {what}{detail}"
