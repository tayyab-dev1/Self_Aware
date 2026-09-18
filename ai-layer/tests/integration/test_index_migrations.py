from __future__ import annotations

from pathlib import Path

import asyncpg
import pytest

from app.index.database import IndexDatabase
from app.index.migrations import MigrationChecksumMismatchError, MigrationError


async def test_the_capability_index_migrations_apply_once(scratch_index: IndexDatabase) -> None:
    assert await scratch_index.apply_migrations() == ["0001", "0002"]
    assert await scratch_index.apply_migrations() == []


async def test_the_capability_index_table_matches_the_plan(
    scratch_index: IndexDatabase, ai_connection: asyncpg.Connection
) -> None:
    await scratch_index.apply_migrations()

    columns = await ai_connection.fetch(
        """SELECT attname, format_type(atttypid, atttypmod) AS type, attnotnull,
                  attgenerated::text AS generated
           FROM pg_attribute
           WHERE attrelid = to_regclass($1) AND attnum > 0 AND NOT attisdropped
           ORDER BY attnum""",
        f"{scratch_index.schema}.capability_index",
    )
    indexes = await ai_connection.fetch(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = $1 AND tablename = 'capability_index'",
        scratch_index.schema,
    )

    assert [tuple(row) for row in columns] == [
        ("capability_id", "text", True, ""),
        ("content", "text", True, ""),
        ("module", "text", True, ""),
        ("read_only", "boolean", True, ""),
        ("embedding", "vector(1024)", False, ""),
        ("tsv", "tsvector", False, "s"),
        ("version", "text", True, ""),
        ("disambiguate_from", "text[]", True, ""),
        ("embedding_model", "text", True, ""),
        ("synced_at", "timestamp with time zone", True, ""),
    ]
    assert any("USING gin (tsv)" in row["indexdef"] for row in indexes)
    assert not any("hnsw" in row["indexdef"] or "ivfflat" in row["indexdef"] for row in indexes)


async def test_the_full_text_column_follows_the_description(
    scratch_index: IndexDatabase, ai_connection: asyncpg.Connection
) -> None:
    await scratch_index.apply_migrations()
    table = f'"{scratch_index.schema}".capability_index'

    await ai_connection.execute(
        f"INSERT INTO {table} (capability_id, content, module, read_only) VALUES ($1, $2, $3, $4)",
        "fee.reminder.send",
        "Sends a fee reminder to the guardians of students who have unpaid fees.",
        "fee",
        False,
    )
    found = await ai_connection.fetchval(
        f"SELECT capability_id FROM {table} WHERE tsv @@ plainto_tsquery('english', $1)",
        "reminders unpaid",
    )

    assert found == "fee.reminder.send"


async def test_editing_an_applied_migration_is_refused(
    scratch_index: IndexDatabase, tmp_path: Path
) -> None:
    migration = tmp_path / "0001_first.sql"
    migration.write_text("CREATE TABLE first_table (id int);")
    await scratch_index.apply_migrations(tmp_path)

    migration.write_text("CREATE TABLE first_table (id bigint);")

    with pytest.raises(MigrationChecksumMismatchError, match="edited after it was applied"):
        await scratch_index.apply_migrations(tmp_path)


async def test_pending_migrations_run_in_version_order(
    scratch_index: IndexDatabase, tmp_path: Path
) -> None:
    (tmp_path / "0002_child.sql").write_text(
        "CREATE TABLE child (parent_id int REFERENCES parent);"
    )
    (tmp_path / "0001_parent.sql").write_text("CREATE TABLE parent (id int PRIMARY KEY);")

    assert await scratch_index.apply_migrations(tmp_path) == ["0001", "0002"]


async def test_a_failing_migration_is_rolled_back_and_not_recorded(
    scratch_index: IndexDatabase, ai_connection: asyncpg.Connection, tmp_path: Path
) -> None:
    (tmp_path / "0001_fine.sql").write_text("CREATE TABLE fine (id int);")
    (tmp_path / "0002_broken.sql").write_text("CREATE TABLE half_done (id int); SELECT 1/0;")

    with pytest.raises(asyncpg.DivisionByZeroError):
        await scratch_index.apply_migrations(tmp_path)

    schema = scratch_index.schema
    assert (
        await ai_connection.fetchval("SELECT to_regclass($1)::text", f"{schema}.half_done") is None
    )
    recorded = await ai_connection.fetch(f'SELECT version FROM "{schema}".schema_migrations')
    assert [row["version"] for row in recorded] == ["0001"]


async def test_a_database_newer_than_the_code_is_refused(
    scratch_index: IndexDatabase, tmp_path: Path
) -> None:
    (tmp_path / "0001_first.sql").write_text("CREATE TABLE first_table (id int);")
    (tmp_path / "0002_second.sql").write_text("CREATE TABLE second_table (id int);")
    await scratch_index.apply_migrations(tmp_path)

    (tmp_path / "0002_second.sql").unlink()

    with pytest.raises(MigrationError, match="older than the database"):
        await scratch_index.apply_migrations(tmp_path)
