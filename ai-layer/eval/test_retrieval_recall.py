"""Phase 4 gate: retrieval finds the right capability for the labelled sentences.

    make ai-eval     (needs Docker and `make embeddings-up`; writes eval/reports/retrieval.md)

The first run embeds about 750 texts on the CPU and takes several minutes; later runs read them
from eval/.cache/. Fails, rather than skips, when the embeddings service is down.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.core.settings import Settings
from app.embeddings.client import EmbeddingsClient
from app.retrieval.hybrid import CANDIDATE_CAP
from eval.conftest import IndexFactory
from eval.retrieval.dataset import dataset_problems, load_distractors, load_sentences
from eval.retrieval.embedding_cache import CachingEmbedder
from eval.retrieval.harness import IndexRun, add_distractors, fill_poc_index, run, sibling_map
from eval.retrieval.metrics import cluster_check, score
from eval.retrieval.report import write_report

pytestmark = [pytest.mark.integration, pytest.mark.embeddings]

RECALL_GATE = 0.90


@dataclass(frozen=True, slots=True)
class EvalRuns:
    poc: IndexRun
    stress: IndexRun
    siblings: dict[str, tuple[str, ...]]
    report: str


@pytest_asyncio.fixture(scope="module")
async def runs(new_index: IndexFactory) -> AsyncIterator[EvalRuns]:
    capabilities = load_snapshot().capabilities
    sentences = load_sentences()
    distractors = load_distractors()
    problems = dataset_problems(sentences, capabilities, distractors)
    assert not problems, f"The eval set is not fit to measure with: {problems}"

    client = EmbeddingsClient.from_settings(Settings())
    try:
        embedder = CachingEmbedder(client)
        await embedder.require_expected_model()

        poc_index = await new_index()
        assert sorted(await fill_poc_index(poc_index, embedder)) == sorted(
            capability.id for capability in capabilities
        )
        poc = await run("POC index", poc_index, embedder, sentences, cap=CANDIDATE_CAP)

        stress_index = await new_index()
        await fill_poc_index(stress_index, embedder)
        await add_distractors(stress_index, embedder, distractors)
        stress = await run(
            "Stress index (with planning-contract distractors)",
            stress_index,
            embedder,
            sentences,
            cap=CANDIDATE_CAP,
        )
    finally:
        await client.aclose()

    siblings = sibling_map(capabilities)
    report = write_report([poc, stress], sentences, siblings, cap=CANDIDATE_CAP)
    yield EvalRuns(poc=poc, stress=stress, siblings=siblings, report=report)


def test_recall_at_30_clears_90_percent_on_the_poc_index(runs: EvalRuns) -> None:
    recall = score(runs.poc.typed).recall_at_cap

    assert recall > RECALL_GATE, f"recall@30 {recall:.1%}; see eval/reports/retrieval.md"


def test_recall_at_30_clears_90_percent_among_distractors_for_english_queries(
    runs: EvalRuns,
) -> None:
    """The stress index, fed what decompose will feed it: English (CLAUDE.md invariant 11)."""
    recall = score(runs.stress.in_english).recall_at_cap

    assert recall > RECALL_GATE, f"recall@30 {recall:.1%}; see eval/reports/retrieval.md"


def test_a_query_that_retrieves_any_cluster_member_retrieves_the_whole_cluster(
    runs: EvalRuns,
) -> None:
    for index_run in (runs.poc, runs.stress):
        clusters = cluster_check(index_run.typed + index_run.glossed, runs.siblings)

        assert clusters.touched > 0
        assert not clusters.broken, [outcome.query for outcome in clusters.broken]


def test_roman_urdu_recall_is_reported_on_its_own(runs: EvalRuns) -> None:
    roman_urdu = [o for o in runs.stress.typed if o.sentence.lang == "ur-Latn"]

    assert roman_urdu
    assert len(runs.stress.glossed) == len(roman_urdu)
    assert "| Roman Urdu, as typed |" in runs.report
    assert "| Roman Urdu, English gloss |" in runs.report
