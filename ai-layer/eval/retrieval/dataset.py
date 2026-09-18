"""The labelled retrieval eval set, and the rules it must keep to be worth measuring.

Sentences come from two files in ``data/``:

  contract_sentences.jsonl  labelled by planning-contracts (scripts/extract_eval_corpus.py)
  authored_sentences.jsonl  written for this eval, weighted towards the confusable cluster

``distractors.jsonl`` holds the other intents' summaries, which fill the stress index.
"""

from __future__ import annotations

import collections
from collections.abc import Sequence
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.gateway.models import CapabilityMetadata

DATA_DIR = Path(__file__).resolve().parent / "data"
SENTENCE_FILES = ("contract_sentences.jsonl", "authored_sentences.jsonl")
DISTRACTOR_FILE = "distractors.jsonl"

MIN_SENTENCES = 150
MAX_SENTENCES = 200
MIN_ROMAN_URDU_SHARE = 0.3
MIN_PER_CLUSTER_MEMBER = 10

Language = Literal["en", "ur-Latn"]


class EvalSentence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: str = Field(min_length=1)
    lang: Language
    expected: str
    source: Literal["contract", "authored"]
    gloss: str | None = None
    origin: str | None = None


class Distractor(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    module: str
    content: str = Field(min_length=1)


def load_sentences(data_dir: Path = DATA_DIR) -> list[EvalSentence]:
    return [
        EvalSentence.model_validate_json(line)
        for name in SENTENCE_FILES
        for line in (data_dir / name).read_text().splitlines()
        if line.strip()
    ]


def load_distractors(data_dir: Path = DATA_DIR) -> list[Distractor]:
    return [
        Distractor.model_validate_json(line)
        for line in (data_dir / DISTRACTOR_FILE).read_text().splitlines()
        if line.strip()
    ]


def cluster_members(capabilities: Sequence[CapabilityMetadata]) -> set[str]:
    """Every capability that declares at least one sibling."""
    return {capability.id for capability in capabilities if capability.disambiguate_from}


def dataset_problems(
    sentences: Sequence[EvalSentence],
    capabilities: Sequence[CapabilityMetadata],
    distractors: Sequence[Distractor] = (),
) -> list[str]:
    """Why this eval set would report a flattering or meaningless number. Empty when sound."""
    problems: list[str] = []
    ids = {capability.id for capability in capabilities}
    if not MIN_SENTENCES <= len(sentences) <= MAX_SENTENCES:
        problems.append(f"{len(sentences)} sentences, expected {MIN_SENTENCES}-{MAX_SENTENCES}")

    unknown = sorted({sentence.expected for sentence in sentences} - ids)
    if unknown:
        problems.append(f"labels that are not published capabilities: {unknown}")

    texts = collections.Counter(" ".join(sentence.text.lower().split()) for sentence in sentences)
    repeated = sorted(text for text, count in texts.items() if count > 1)
    if repeated:
        problems.append(f"sentences listed twice: {repeated}")

    roman_urdu = [sentence for sentence in sentences if sentence.lang == "ur-Latn"]
    if len(roman_urdu) < MIN_ROMAN_URDU_SHARE * len(sentences):
        problems.append(f"only {len(roman_urdu)} Roman Urdu sentences")
    other_script = [
        sentence.text
        for sentence in roman_urdu
        if any(char.isalpha() and not char.isascii() for char in sentence.text)
    ]
    if other_script:
        problems.append(f"Roman Urdu sentences with non-Latin letters: {other_script}")
    unglossed = [sentence.text for sentence in roman_urdu if not sentence.gloss]
    if unglossed:
        problems.append(f"Roman Urdu sentences without an English gloss: {unglossed}")

    per_label = collections.Counter(sentence.expected for sentence in sentences)
    uncovered = sorted(capability_id for capability_id in ids if per_label[capability_id] == 0)
    if uncovered:
        problems.append(f"capabilities no sentence targets: {uncovered}")
    thin = sorted(
        f"{member} ({per_label[member]})"
        for member in cluster_members(capabilities)
        if per_label[member] < MIN_PER_CLUSTER_MEMBER
    )
    if thin:
        problems.append(f"confusable cluster members with under {MIN_PER_CLUSTER_MEMBER}: {thin}")

    clashing = sorted({distractor.id for distractor in distractors} & ids)
    if clashing:
        problems.append(f"distractors that reuse a published capability id: {clashing}")
    return problems
