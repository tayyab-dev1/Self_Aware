"""Writes the retrieval eval as a Markdown report: eval/reports/retrieval.md."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path

from app.llm.runner import DECOMPOSE_MODEL
from app.sync.metadata_sync import EMBEDDING_MODEL
from eval.retrieval.dataset import EvalSentence
from eval.retrieval.harness import IndexRun
from eval.retrieval.intents import DecomposedSentence
from eval.retrieval.metrics import TOP_KS, Outcome, Scores, breakdown, cluster_check, score

REPORT_PATH = Path(__file__).resolve().parents[1] / "reports" / "retrieval.md"
WORST_SHOWN = 25


def _row(label: str, scores: Scores) -> str:
    recall = " | ".join(f"{scores.recall_at[k]:.1%}" for k in TOP_KS)
    at_cap = f"**{scores.recall_at_cap:.1%}**"
    return f"| {label} | {scores.count} | {at_cap} | {recall} | {scores.mrr:.3f} |"


def _table(rows: Sequence[tuple[str, Scores]], cap: int) -> list[str]:
    heading = " | ".join(f"@{k}" for k in TOP_KS)
    return [
        f"| | n | recall@{cap} | {heading} | MRR |",
        "|---|---:|---:|" + "---:|" * len(TOP_KS) + "---:|",
        *(_row(label, scores) for label, scores in rows),
    ]


def _groups(run: IndexRun) -> list[tuple[str, Scores]]:
    by_lang = breakdown(run.typed, lambda o: o.sentence.lang)
    return [
        ("in English: English as typed, Roman Urdu glossed", score(run.in_english)),
        ("all sentences, as typed", score(run.typed)),
        ("English", by_lang.get("en", score([]))),
        ("Roman Urdu, as typed", by_lang.get("ur-Latn", score([]))),
        ("Roman Urdu, English gloss", score(run.glossed)),
    ]


def _misses(outcomes: Sequence[Outcome], cap: int) -> list[str]:
    worst = sorted(
        (o for o in outcomes if o.fused_rank is None or o.fused_rank > 3),
        key=lambda o: (o.retrieved, -(o.fused_rank or 10_000)),
    )[:WORST_SHOWN]
    lines = [
        "| sentence | lang | expected | fused rank | top 3 | in top " + str(cap) + " |",
        "|---|---|---|---:|---|---|",
    ]
    for o in worst:
        lines.append(
            f"| {o.query} | {o.sentence.lang} | `{o.sentence.expected}` | {o.fused_rank or '-'} | "
            f"{', '.join(f'`{c}`' for c in o.top)} | {'yes' if o.retrieved else '**no**'} |"
        )
    return lines


def write_report(
    runs: Sequence[IndexRun],
    sentences: Sequence[EvalSentence],
    siblings: Mapping[str, Sequence[str]],
    *,
    cap: int,
    path: Path = REPORT_PATH,
) -> str:
    contract = sum(1 for s in sentences if s.source == "contract")
    roman_urdu = sum(1 for s in sentences if s.lang == "ur-Latn")
    lines = [
        "# Retrieval eval",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make ai-eval`. "
        f"Model `{EMBEDDING_MODEL}`, candidate cap {cap}, RRF k 60.",
        "",
        f"{len(sentences)} labelled sentences: {contract} from planning-contracts, "
        f"{len(sentences) - contract} written for this eval; {roman_urdu} Roman Urdu, "
        f"{len(sentences) - roman_urdu} English.",
        "",
        "The first row is what retrieval will receive once decompose (Phase 5) turns every "
        "sentence into English intents; the English gloss of a Roman Urdu sentence stands in for "
        "those intents until then. The as-typed rows show what retrieval does without that step.",
        "",
        "Recall@" + str(cap) + " counts the expected capability anywhere in the candidates the "
        "planner would see (siblings included). @1/@3/@5 and MRR use the fused rank from the two "
        "searches alone, before siblings are pulled in.",
        "",
    ]
    for run in runs:
        clusters = cluster_check(run.typed + run.glossed, siblings)
        lines += [
            f"## {run.name} ({run.size} capabilities indexed)",
            "",
            *_table(_groups(run), cap),
            "",
            f"Confusable clusters arrived whole in {clusters.whole} of the {clusters.touched} "
            "queries that retrieved any member.",
            "",
            "By expected capability (as typed):",
            "",
            *_table(list(breakdown(run.typed, lambda o: o.sentence.expected).items()), cap),
            "",
            "By where the sentence came from (as typed):",
            "",
            *_table(list(breakdown(run.typed, lambda o: o.sentence.source).items()), cap),
            "",
            f"Hardest sentences (as typed, fused rank worse than 3, at most {WORST_SHOWN}):",
            "",
            *_misses(run.typed, cap),
            "",
        ]
    text = "\n".join(lines)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return text


INTENTS_REPORT_PATH = REPORT_PATH.with_name("retrieval_with_intents.md")


def write_intents_report(
    outcomes: Sequence[Outcome],
    decomposed: Mapping[str, DecomposedSentence],
    siblings: Mapping[str, Sequence[str]],
    *,
    size: int,
    cap: int,
    path: Path = INTENTS_REPORT_PATH,
) -> str:
    by_lang = breakdown(outcomes, lambda o: o.sentence.lang)
    failures = {text: d.problems for text, d in decomposed.items() if d.problems}
    counts = [len(d.intents) for d in decomposed.values() if not d.problems]
    clusters = cluster_check(outcomes, siblings)
    lines = [
        "# Retrieval eval with real intents",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make ai-eval`. "
        f"Every sentence was decomposed by `{DECOMPOSE_MODEL}`, and retrieval searched with its "
        f"intents on the stress index ({size} capabilities indexed). Model `{EMBEDDING_MODEL}`, "
        f"candidate cap {cap}.",
        "",
        f"Decompose answered {len(decomposed) - len(failures)} of {len(decomposed)} sentences "
        f"within the rules ({sum(counts)} intents; "
        f"split into more than one: {sum(1 for c in counts if c > 1)}). "
        f"{len(failures)} broke a rule and retrieve nothing here.",
        "",
        *_table(
            [
                ("all sentences, through intents", score(outcomes)),
                ("English, through intents", by_lang.get("en", score([]))),
                ("Roman Urdu, through intents", by_lang.get("ur-Latn", score([]))),
            ],
            cap,
        ),
        "",
        f"Confusable clusters arrived whole in {clusters.whole} of the {clusters.touched} "
        "queries that retrieved any member.",
        "",
        "By expected capability:",
        "",
        *_table(list(breakdown(outcomes, lambda o: o.sentence.expected).items()), cap),
        "",
        "Hardest sentences (the intents searched; fused rank worse than 3, "
        f"at most {WORST_SHOWN}):",
        "",
        *_misses(outcomes, cap),
        "",
    ]
    if failures:
        lines += ["Sentences decompose could not answer within the rules:", ""]
        lines += [f"- {text}: {', '.join(codes)}" for text, codes in sorted(failures.items())]
        lines.append("")
    text = "\n".join(lines)
    path.write_text(text)
    return text
