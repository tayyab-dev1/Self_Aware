"""Printing the four numbers, and writing the detail behind them to eval/reports/measure.md."""

from __future__ import annotations

import collections
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path

from app.llm.runner import DECOMPOSE_MODEL, PLANNER_MODEL
from eval.measure.faults import FaultTally
from eval.measure.pipeline import CaseResult
from eval.measure.scores import GROUPS, FourNumbers

REPORT_PATH = Path(__file__).resolve().parents[1] / "reports" / "measure.md"
NAMES = {
    "recall_at_30": "recall@30",
    "plan_accuracy": "plan accuracy",
    "refusal_correctness": "refusal correctness",
    "validator_catch_rate": "validator catch rate",
}
LABELS = {"all": "all", "en": "English", "ur-Latn": "Roman Urdu"}


def table(
    numbers: Mapping[str, FourNumbers], baseline: Mapping[str, Mapping[str, float]] | None
) -> str:
    """The four numbers as a plain table, with the change from the baseline when there is one."""
    lines = [f"{'':<22}" + "".join(f"{LABELS[g]:<16}" for g in GROUPS)]
    for key, name in NAMES.items():
        cells = []
        for group in GROUPS:
            value = getattr(numbers[group], key)
            cell = f"{value:6.1%}"
            if baseline and group in baseline and key in baseline[group]:
                change = (value - baseline[group][key]) * 100
                if abs(change) >= 0.05:
                    cell += f" ({change:+.1f})"
            cells.append(f"{cell:<16}")
        lines.append(f"{name:<22}" + "".join(cells).rstrip())
    counts = numbers["all"]
    lines.append(
        f"out of: {counts.sentences} labelled sentences, {counts.refusal_cases} to refuse, "
        f"{counts.faults_applied} injected faults"
    )
    return "\n".join(lines)


def write_report(
    results: Sequence[CaseResult],
    numbers: Mapping[str, FourNumbers],
    faults: Mapping[str, Mapping[str, FaultTally]],
    baseline: Mapping[str, Mapping[str, float]] | None,
    *,
    index_size: int,
    path: Path = REPORT_PATH,
) -> str:
    positives = [r for r in results if r.case.expected is not None]
    refusals = [r for r in results if r.case.expected is None]
    lines = [
        "# Measure: the four numbers",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make measure`. "
        f"Decompose `{DECOMPOSE_MODEL}`, planner `{PLANNER_MODEL}`, answers from "
        f"`eval/recordings/`, stress index of {index_size} capabilities, "
        "today fixed at 2026-09-14.",
        "",
        "```",
        table(numbers, baseline),
        "```",
        "",
        "- **recall@30**: the expected capability is among the candidates retrieved with "
        "decompose's intents.",
        "- **plan accuracy**: the answer is exactly one step (or a request for input) for the "
        "expected capability.",
        "- **refusal correctness**: across both sets, the system acted exactly when it should: "
        "planned or asked for a labelled sentence, refused (or failed safely) for a sentence to "
        "refuse.",
        "- **validator catch rate**: of the faults injected into planner answers the validator had "
        "accepted, the share it refused. The change in brackets is against "
        "`eval/measure/baseline.json`.",
        "",
        "## Outcomes",
        "",
        "| set | n | plan | needs input | refusal | invalid answer | model call failed |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name, chosen in (("labelled sentences", positives), ("sentences to refuse", refusals)):
        count = collections.Counter(r.outcome for r in chosen)
        lines.append(
            f"| {name} | {len(chosen)} | {count['plan']} | {count['needs_input']} | "
            f"{count['refusal']} | {count['invalid']} | {count['failed']} |"
        )
    problems = collections.Counter(
        code for r in results if r.outcome == "invalid" for code in r.problems
    )
    if problems:
        lines += [
            "",
            "Rules broken by the answers the validator refused: "
            + ", ".join(f"`{code}` {n}" for code, n in problems.most_common())
            + ".",
        ]

    lines += [
        "",
        "## Plan accuracy by capability",
        "",
        "| expected | n | correct | planned instead |",
        "|---|---:|---:|---|",
    ]
    by_expected: dict[str, list[CaseResult]] = collections.defaultdict(list)
    for result in positives:
        assert result.case.expected is not None
        by_expected[result.case.expected].append(result)
    for expected, chosen in sorted(by_expected.items()):
        wrong = collections.Counter(
            ", ".join(r.capabilities) if r.acted else r.outcome
            for r in chosen
            if not r.planned_correctly
        )
        lines.append(
            f"| `{expected}` | {len(chosen)} | {sum(r.planned_correctly for r in chosen)} | "
            + ", ".join(f"{what} (x{n})" for what, n in wrong.most_common())
            + " |"
        )

    lines += [
        "",
        "## Validator faults",
        "",
        "| fault | applied | caught | for the right reason |",
        "|---|---:|---:|---:|",
    ]
    for name, tally in faults.get("all", {}).items():
        lines.append(f"| {name} | {tally.applied} | {tally.caught} | {tally.right_reason} |")

    wrong_plans = [r for r in positives if not r.planned_correctly]
    lines += ["", f"## Labelled sentences not planned correctly ({len(wrong_plans)})", ""]
    lines += ["| sentence | lang | expected | outcome | detail |", "|---|---|---|---|---|"]
    for r in wrong_plans:
        detail = ", ".join(r.capabilities or r.problems) or (
            "not retrieved" if not r.retrieved else ""
        )
        lines.append(
            f"| {r.case.text} | {r.case.lang} | `{r.case.expected}` | {r.outcome} | {detail} |"
        )

    acted_wrongly = [r for r in refusals if r.acted]
    lines += ["", f"## Sentences to refuse that were acted on ({len(acted_wrongly)})", ""]
    lines += ["| sentence | lang | contract intent | planned |", "|---|---|---|---|"]
    for r in acted_wrongly:
        origin = r.case.origin or r.case.source
        lines.append(f"| {r.case.text} | {r.case.lang} | {origin} | {', '.join(r.capabilities)} |")

    text = "\n".join(lines) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return text
