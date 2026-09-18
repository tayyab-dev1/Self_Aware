from __future__ import annotations

from pathlib import Path

import pytest

from app.index.migrations import (
    MIGRATIONS_DIR,
    InvalidMigrationFileError,
    discover_migrations,
    quote_schema,
)


def test_shipped_migrations_are_numbered_without_gaps() -> None:
    versions = [migration.version for migration in discover_migrations(MIGRATIONS_DIR)]

    assert versions == [f"{number:04d}" for number in range(1, len(versions) + 1)]


def test_the_first_migration_creates_the_index_table_from_the_plan() -> None:
    first = discover_migrations(MIGRATIONS_DIR)[0]

    assert first.name == "capability_index"
    assert "CREATE TABLE capability_index" in first.sql
    assert "vector(1024)" in first.sql
    assert "to_tsvector('english', content)" in first.sql


@pytest.mark.parametrize(
    "filename", ["1_short.sql", "0001-dashes.sql", "0001_Upper.sql", "0001_.sql", "0001_a__b.sql"]
)
def test_badly_named_files_are_rejected(tmp_path: Path, filename: str) -> None:
    (tmp_path / filename).write_text("SELECT 1;")

    with pytest.raises(InvalidMigrationFileError, match="must be named"):
        discover_migrations(tmp_path)


def test_a_version_used_twice_is_rejected(tmp_path: Path) -> None:
    (tmp_path / "0001_first.sql").write_text("SELECT 1;")
    (tmp_path / "0001_second.sql").write_text("SELECT 2;")

    with pytest.raises(InvalidMigrationFileError, match="used twice"):
        discover_migrations(tmp_path)


def test_editing_a_file_changes_its_checksum(tmp_path: Path) -> None:
    path = tmp_path / "0001_first.sql"
    path.write_text("CREATE TABLE a (id int);")
    before = discover_migrations(tmp_path)[0].checksum

    path.write_text("CREATE TABLE a (id bigint);")

    assert discover_migrations(tmp_path)[0].checksum != before


def test_schema_names_are_validated_before_they_reach_sql() -> None:
    assert quote_schema("ai_layer") == '"ai_layer"'
    with pytest.raises(ValueError, match="Invalid schema name"):
        quote_schema('ai_layer"; DROP SCHEMA school; --')
