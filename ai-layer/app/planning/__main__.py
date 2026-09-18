"""Plan a sentence end to end and show every stage: intents, candidates, the checked plan, and what
the backend's preflight says about it. Nothing is executed.

    uv run python -m app.planning "class 5 blue ke defaulters ko whatsapp par reminder bhejo"
    (or: make plan Q="...")

Needs the backend, the embeddings service, an index the AI layer has synced, and the Claude CLI.
Acts as the dev user: the token is read from BACKEND_DEV_USER_TOKEN, and never printed.
"""

from __future__ import annotations

import asyncio
import os
import sys
import uuid

import structlog

from app.core.logging import configure_logging
from app.core.settings import get_settings
from app.decompose.decomposer import Decomposer
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.gateway.errors import GatewayRejectedError
from app.index.database import IndexDatabase
from app.llm.claude_cli import ClaudeCliModel
from app.planning.outcomes import PlannedSteps
from app.planning.planner import Planner
from app.planning.service import SentencePlanner
from app.retrieval.hybrid import HybridRetriever
from domain.school.calendar import SCHOOL_TIME_ZONE, school_today
from domain.school.glossary import RECORD_WORDS, glossary_lines


async def _plan(sentence: str) -> int:
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_format)
    log = structlog.get_logger("app.planning")
    token = os.environ.get("BACKEND_DEV_USER_TOKEN", "")
    if not sentence or not token:
        log.error("usage", example='BACKEND_DEV_USER_TOKEN=... python -m app.planning "sentence"')
        return 2

    model = ClaudeCliModel.from_settings(settings)
    gateway = GatewayClient.from_settings(settings)
    index = await IndexDatabase.connect(settings)
    embeddings = EmbeddingsClient.from_settings(settings)
    try:
        catalog = {c.id: c for c in (await gateway.get_metadata()).capabilities}
        allowed = (await gateway.get_session_capabilities(token)).capability_ids
        planner = SentencePlanner(
            Decomposer(model, glossary_lines()),
            HybridRetriever(index, embeddings, cap=settings.retrieval_candidate_cap),
            Planner(
                model,
                max_steps=settings.plan_max_steps,
                today=school_today,
                time_zone=SCHOOL_TIME_ZONE,
                record_words=RECORD_WORDS,
            ),
            lambda: catalog,
        )
        understanding = await planner.understand(
            sentence, allowed=allowed, session_id=f"cli-{uuid.uuid4().hex[:8]}"
        )
        for intent in understanding.decomposition.intents:
            log.info("intent", text=intent.text, entities=list(intent.entities))
        log.info("candidates", ids=list(understanding.candidates))
        outcome = understanding.outcome
        if not isinstance(outcome, PlannedSteps):
            log.info("outcome", result=outcome)
            return 0
        for step in outcome.plan.steps:
            log.info(
                "step",
                step=step.step,
                capability_id=step.capability_id,
                params={k: v.model_dump(exclude_none=True) for k, v in step.params.items()},
            )
        try:
            confirmed = await gateway.preflight(outcome.plan, token)
            log.info("preflight_confirmation", confirmation=confirmed.confirmation)
        except GatewayRejectedError as rejected:
            log.info("preflight_refused", code=rejected.code, message=rejected.message)
    finally:
        await embeddings.aclose()
        await index.close()
        await gateway.aclose()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_plan(" ".join(sys.argv[1:]))))
