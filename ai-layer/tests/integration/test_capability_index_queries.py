"""The index queries sync and retrieval rely on, against real Postgres + pgvector."""

from __future__ import annotations

import math
from collections.abc import Sequence

from app.index.database import CapabilityRow, IndexDatabase, IndexedVersion
from app.sync.metadata_sync import EMBEDDING_MODEL

DIMENSIONS = 1024


def direction(*weights: float) -> list[float]:
    """A unit vector whose first components are ``weights``."""
    norm = math.sqrt(sum(w * w for w in weights))
    return [w / norm for w in weights] + [0.0] * (DIMENSIONS - len(weights))


def row(
    capability_id: str,
    content: str,
    embedding: Sequence[float],
    *,
    siblings: Sequence[str] = (),
    model: str = EMBEDDING_MODEL,
    version: str = "v1",
) -> CapabilityRow:
    return CapabilityRow(
        capability_id=capability_id,
        content=content,
        module=capability_id.split(".")[0],
        read_only=False,
        embedding=embedding,
        version=version,
        disambiguate_from=siblings,
        embedding_model=model,
    )


ROWS = [
    row(
        "fee.reminder.send",
        "Sends a fee reminder by WhatsApp or SMS to guardians with overdue fees.",
        direction(1, 0, 0),
        siblings=["fee.overdue.list"],
    ),
    row(
        "fee.overdue.list",
        "Shows who has not paid their fees and how much each family owes.",
        direction(0.8, 0.6, 0),
        siblings=["fee.reminder.send"],
    ),
    row("dashboard.main.read", "Shows the dashboard figures collected today.", direction(0, 0, 1)),
    row("stale.model", "Sends reminders about fees.", direction(1, 0, 0), model="old@abc"),
]
EVERYONE = [r.capability_id for r in ROWS]


async def filled(index: IndexDatabase) -> IndexDatabase:
    await index.apply_migrations()
    await index.apply_sync(ROWS, [])
    return index


async def test_sync_writes_are_upserts_and_removals(scratch_index: IndexDatabase) -> None:
    index = await filled(scratch_index)

    rewritten = row("fee.reminder.send", "Sends fee reminders.", direction(0, 1, 0), version="v2")
    await index.apply_sync([rewritten], ["dashboard.main.read"])

    versions = await index.indexed_versions()
    assert versions["fee.reminder.send"] == IndexedVersion("v2", EMBEDDING_MODEL)
    assert "dashboard.main.read" not in versions
    assert len(versions) == 3


async def test_dense_ranking_orders_by_cosine_within_the_allow_list_and_model(
    scratch_index: IndexDatabase,
) -> None:
    index = await filled(scratch_index)
    query = direction(1, 0.1, 0)

    assert await index.dense_ranking(query, EVERYONE, EMBEDDING_MODEL, 10) == [
        "fee.reminder.send",
        "fee.overdue.list",
        "dashboard.main.read",
    ]
    assert await index.dense_ranking(query, ["dashboard.main.read"], EMBEDDING_MODEL, 10) == [
        "dashboard.main.read"
    ]
    assert await index.dense_ranking(query, EVERYONE, EMBEDDING_MODEL, 1) == ["fee.reminder.send"]


async def test_lexical_ranking_matches_any_word_with_stemming(scratch_index: IndexDatabase) -> None:
    index = await filled(scratch_index)

    ranked = await index.lexical_ranking("remind the guardians on WhatsApp", EVERYONE, 10)
    assert ranked[0] == "fee.reminder.send"
    assert "dashboard.main.read" not in ranked

    allowed = ["dashboard.main.read"]
    assert await index.lexical_ranking("fees collected today", allowed, 10) == allowed


async def test_a_query_of_stop_words_matches_nothing(scratch_index: IndexDatabase) -> None:
    index = await filled(scratch_index)

    assert await index.lexical_ranking("the and of", EVERYONE, 10) == []


async def test_siblings_are_read_for_allowed_capabilities_only(
    scratch_index: IndexDatabase,
) -> None:
    index = await filled(scratch_index)

    siblings = await index.siblings(["fee.reminder.send", "dashboard.main.read", "not.indexed"])

    assert siblings == {"fee.reminder.send": ("fee.overdue.list",), "dashboard.main.read": ()}
