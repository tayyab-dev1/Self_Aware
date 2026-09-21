"""Retrieval as it really runs: searching with decompose's English intents, not the typed sentence.

    make ai-eval     (records decompose's answers in eval/recordings/ once, then replays them)

Phase 4 measured English glosses as a stand-in for decompose (README decision 40). This replaces
them with real intents, on the stress index, and holds them to the same gate. Writes
eval/reports/retrieval_with_intents.md.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.core.settings import Settings
from app.decompose.decomposer import THINKING, system_prompt
from app.embeddings.client import EmbeddingsClient
from app.llm.gemini import GeminiModel
from app.llm.runner import DECOMPOSE_MODEL
from app.retrieval.hybrid import CANDIDATE_CAP, HybridRetriever
from domain.school.glossary import glossary_lines
from eval.conftest import IndexFactory
from eval.measure.recordings import Recordings, mode_from_environment
from eval.retrieval.dataset import load_distractors, load_sentences
from eval.retrieval.embedding_cache import CachingEmbedder
from eval.retrieval.harness import add_distractors, fill_poc_index, retrieve_for, sibling_map
from eval.retrieval.intents import decompose_all
from eval.retrieval.metrics import Outcome, cluster_check, score
from eval.retrieval.report import write_intents_report

pytestmark = [pytest.mark.integration, pytest.mark.embeddings, pytest.mark.model]

RECALL_GATE = 0.90


@dataclass(frozen=True, slots=True)
class IntentRun:
    outcomes: tuple[Outcome, ...]
    failed_decompositions: int
    report: str


@pytest_asyncio.fixture(scope="module")
async def intent_run(new_index: IndexFactory) -> AsyncIterator[IntentRun]:
    capabilities = load_snapshot().capabilities
    sentences = load_sentences()
    mode = mode_from_environment()
    system = system_prompt(glossary_lines())
    recordings = Recordings("decompose", DECOMPOSE_MODEL, system, mode=mode, thinking=THINKING)
    model = GeminiModel.from_settings(Settings()) if mode == "record" else None
    decomposed = await decompose_all([s.text for s in sentences], recordings, model)

    client = EmbeddingsClient.from_settings(Settings())
    try:
        embedder = CachingEmbedder(client)
        await embedder.require_expected_model()
        index = await new_index()
        await fill_poc_index(index, embedder)
        await add_distractors(index, embedder, load_distractors())
        allowed = sorted(await index.indexed_versions())
        retriever = HybridRetriever(index, embedder, cap=CANDIDATE_CAP)
        await embedder.embed([intent for d in decomposed.values() for intent in d.intents])
        outcomes = tuple(
            [
                await retrieve_for(
                    retriever, allowed, sentence, list(decomposed[sentence.text].intents)
                )
                for sentence in sentences
            ]
        )
    finally:
        await client.aclose()

    failed = sum(1 for d in decomposed.values() if d.problems)
    report = write_intents_report(
        outcomes, decomposed, sibling_map(capabilities), size=len(allowed), cap=CANDIDATE_CAP
    )
    yield IntentRun(outcomes=outcomes, failed_decompositions=failed, report=report)


def test_recall_at_30_with_real_intents_clears_90_percent_among_distractors(
    intent_run: IntentRun,
) -> None:
    recall = score(intent_run.outcomes).recall_at_cap

    assert recall > RECALL_GATE, (
        f"recall@30 {recall:.1%}; see eval/reports/retrieval_with_intents.md"
    )


def test_roman_urdu_through_real_intents_is_reported_and_clusters_stay_whole(
    intent_run: IntentRun,
) -> None:
    roman_urdu = [o for o in intent_run.outcomes if o.sentence.lang == "ur-Latn"]

    assert roman_urdu
    assert "| Roman Urdu, through intents |" in intent_run.report
    assert not cluster_check(intent_run.outcomes, sibling_map(load_snapshot().capabilities)).broken
