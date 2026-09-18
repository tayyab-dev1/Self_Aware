"""Decompose every eval sentence with Haiku 4.5, through eval/measure/recordings.py.

A sentence whose answer broke a rule is kept as its problem codes: it retrieves nothing, and counts
against recall like any other miss.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from dataclasses import dataclass

from app.decompose.decomposer import Decomposer
from app.llm.runner import ModelError, StructuredModel
from app.validation.problems import InvalidModelOutputError
from domain.school.glossary import glossary_lines
from eval.measure.recordings import ModelCallFailedError, Recordings

CONCURRENT_CALLS = 6


@dataclass(frozen=True, slots=True)
class DecomposedSentence:
    intents: tuple[str, ...]
    problems: tuple[str, ...] = ()


async def decompose(
    sentence: str, recordings: Recordings, model: StructuredModel | None
) -> DecomposedSentence:
    decomposer = Decomposer(recordings.model_for(sentence, model), glossary_lines())
    try:
        return DecomposedSentence(tuple((await decomposer.decompose(sentence)).texts))
    except InvalidModelOutputError as exc:
        return DecomposedSentence((), tuple(exc.codes))
    except (ModelCallFailedError, ModelError) as exc:
        return DecomposedSentence((), (f"MODEL_CALL_FAILED: {type(exc).__name__}",))


async def decompose_all(
    sentences: Sequence[str], recordings: Recordings, model: StructuredModel | None
) -> dict[str, DecomposedSentence]:
    gate = asyncio.Semaphore(CONCURRENT_CALLS)

    async def one(sentence: str) -> tuple[str, DecomposedSentence]:
        async with gate:
            return sentence, await decompose(sentence, recordings, model)

    return dict(await asyncio.gather(*(one(s) for s in dict.fromkeys(sentences))))
