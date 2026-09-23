"""Decompose gives the same, right number of intents every time it sees a sentence.

    make decompose-repeat   records any run not yet in eval/recordings/decompose_repeat.json

The number of intents is the plan's skeleton: each intent is searched on its own, the chooser picks
one capability per intent, and the planner writes one step per request. A merge silently drops an
action ("record 2000 for Ahmed and 3000 for Hamza" as one intent), a split adds one nobody asked
for, and a count that changes between runs makes the same message behave differently. The rest of
the eval has only single-request sentences, so this is where both show up (README decision 76).

Every sentence in eval/measure/data/request_counts.jsonl is decomposed RUNS times, each run a
separate recorded answer. The run fails if any answer has the wrong number of intents. Writes
eval/reports/decompose_repeat.md. Needs no Docker and no embeddings service; ``make measure-ci``
replays it from the recordings.
"""

from __future__ import annotations

import asyncio
import json
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

import pytest
import pytest_asyncio
from pydantic import BaseModel, ConfigDict, Field

from app.core.settings import Settings
from app.decompose.decomposer import THINKING, system_prompt
from app.llm.gemini import GeminiModel
from app.llm.runner import DECOMPOSE_MODEL
from domain.school.glossary import glossary_lines
from eval.measure.pacing import Paced
from eval.measure.recordings import Recordings, mode_from_environment
from eval.retrieval.intents import decompose

pytestmark = [pytest.mark.model]

DATA = Path(__file__).resolve().parent / "measure" / "data" / "request_counts.jsonl"
REPORT = Path(__file__).resolve().parent / "reports" / "decompose_repeat.md"
RUNS = 3
CONCURRENT_CALLS = 2
# The free Gemini tier allows 15 requests a minute; a decompose call takes 2-4 s, so even two at a
# time would go over. Live calls are spaced to stay under it; replayed answers are not slowed.
LIVE_CALLS_PER_MINUTE = 12

# Sentences decompose still gets wrong, if any. Held here so the run fails on a new one and says
# when one starts passing. The first three (README decision 76) were fixed by the prompt rule that
# the same thing asked for two people, classes or sections is one intent each.
KNOWN_GAPS: dict[str, str] = {}


class RequestCount(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: str
    lang: Literal["en", "ur-Latn"]
    requests: int = Field(ge=1, le=3)
    source: Literal["contract", "authored"]
    gloss: str | None = None


def load_request_counts() -> list[RequestCount]:
    return [
        RequestCount.model_validate_json(line)
        for line in DATA.read_text().splitlines()
        if line.strip()
    ]


def run_key(text: str, run: int) -> str:
    """Each run is its own recorded answer, so a replay sees the same variety the model gave."""
    return f"{text}␟run {run}"


@dataclass(frozen=True, slots=True)
class Repeated:
    sentence: RequestCount
    counts: tuple[int | None, ...]  # None: the answer broke a rule, or the call failed
    intents: tuple[tuple[str, ...], ...]

    @property
    def right(self) -> bool:
        return all(count == self.sentence.requests for count in self.counts)

    @property
    def steady(self) -> bool:
        return len(set(self.counts)) == 1


@pytest_asyncio.fixture(scope="module")
async def repeated() -> list[Repeated]:
    mode = mode_from_environment()
    recordings = Recordings(
        "decompose_repeat",
        DECOMPOSE_MODEL,
        system_prompt(glossary_lines()),
        mode=mode,
        thinking=THINKING,
    )
    gemini = GeminiModel.from_settings(Settings()) if mode == "record" else None
    model = Paced(gemini, LIVE_CALLS_PER_MINUTE) if gemini is not None else None
    gate = asyncio.Semaphore(CONCURRENT_CALLS)
    sentences = load_request_counts()

    async def one(sentence: RequestCount, run: int) -> tuple[int | None, tuple[str, ...]]:
        async with gate:
            # The key names the run; the model is still asked with the sentence alone.
            decomposed = await decompose(
                sentence.text, recordings, model, key=run_key(sentence.text, run)
            )
        intents = decomposed.intents
        return (len(intents) if intents else None), intents

    try:
        answers = await asyncio.gather(
            *(one(sentence, run) for sentence in sentences for run in range(1, RUNS + 1))
        )
    finally:
        if gemini is not None:
            await gemini.aclose()

    results = [
        Repeated(
            sentence,
            tuple(count for count, _ in answers[i * RUNS : (i + 1) * RUNS]),
            tuple(intents for _, intents in answers[i * RUNS : (i + 1) * RUNS]),
        )
        for i, sentence in enumerate(sentences)
    ]
    write_report(results)
    return results


def write_report(results: list[Repeated]) -> None:
    right = sum(r.right for r in results)
    steady = sum(r.steady for r in results)
    by_requests = Counter(r.sentence.requests for r in results)
    lines = [
        "# Decompose: the same, right number of intents every time",
        "",
        f"Generated {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} by `make decompose-repeat`. "
        f"Model `{DECOMPOSE_MODEL}`, each sentence decomposed {RUNS} times, answers from "
        "`eval/recordings/decompose_repeat.json`.",
        "",
        f"- **right number of intents in every run**: {right} of {len(results)} sentences",
        f"- **same number in every run**: {steady} of {len(results)}",
        f"- **known gaps** (decision 76, not yet fixed): {len(KNOWN_GAPS)}",
        "- **sentences by requests**: "
        + ", ".join(f"{n}: {by_requests[n]}" for n in sorted(by_requests)),
        "",
        "| sentence | lang | requests | intents per run | right |",
        "|---|---|---:|---|---|",
    ]
    for r in results:
        counts = ", ".join("broken" if c is None else str(c) for c in r.counts)
        verdict = (
            "yes"
            if r.right
            else ("**no**, known gap" if r.sentence.text in KNOWN_GAPS else "**no**")
        )
        lines.append(
            f"| {r.sentence.text} | {r.sentence.lang} | {r.sentence.requests} | {counts} | "
            f"{verdict} |"
        )
    wrong = [r for r in results if not r.right]
    if wrong:
        lines += ["", "## Intents for the sentences counted wrongly", ""]
        for r in wrong:
            gap = KNOWN_GAPS.get(r.sentence.text)
            lines.append(f"- {r.sentence.text}" + (f" — known gap: {gap}" if gap else ""))
            for n, intents in enumerate(r.intents, start=1):
                lines.append(f"  - run {n}: {json.dumps(list(intents), ensure_ascii=False)}")
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n")


def test_the_sentences_cover_single_and_multi_request_in_both_languages() -> None:
    sentences = load_request_counts()

    assert len({s.text for s in sentences}) == len(sentences)
    assert {(s.requests > 1, s.lang) for s in sentences} == {
        (False, "en"), (False, "ur-Latn"), (True, "en"), (True, "ur-Latn"),
    }  # fmt: skip
    assert all(s.gloss for s in sentences if s.lang == "ur-Latn")


def test_every_run_gives_the_right_number_of_intents(
    repeated: list[Repeated], capsys: pytest.CaptureFixture[str]
) -> None:
    right = sum(r.right for r in repeated)
    fixed = [r.sentence.text for r in repeated if r.right and r.sentence.text in KNOWN_GAPS]
    with capsys.disabled():
        print(  # noqa: T201
            f"\n\nDecompose, {RUNS} runs each: right number of intents every time for {right} of "
            f"{len(repeated)} sentences, {len(KNOWN_GAPS)} known gaps. "
            "Detail: eval/reports/decompose_repeat.md\n"
        )
        for text in fixed:
            print(f"Now right, so drop it from KNOWN_GAPS: {text}")  # noqa: T201

    wrong = {r.sentence.text: r.counts for r in repeated if not r.right}
    new = {text: counts for text, counts in wrong.items() if text not in KNOWN_GAPS}
    assert not new, f"Wrong number of intents (expected per sentence in {DATA.name}): {new}"


def test_every_known_gap_is_a_sentence_that_still_fails(repeated: list[Repeated]) -> None:
    """A gap that no longer exists, or names no sentence, must not sit here unnoticed."""
    texts = {r.sentence.text for r in repeated}

    assert set(KNOWN_GAPS) <= texts
