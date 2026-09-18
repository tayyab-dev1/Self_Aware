"""Plain-SQL migrations for the capability index.

Files live in ``ai-layer/migrations/`` and are named ``NNNN_description.sql``. Each runs once,
in version order, in its own transaction, and is recorded in ``schema_migrations`` with a
checksum. Editing a file that has already run is an error: add a new file instead.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

import asyncpg
import structlog

log = structlog.get_logger(__name__)

MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "migrations"

_FILENAME = re.compile(r"^(?P<version>\d{4})_(?P<name>[a-z0-9]+(?:_[a-z0-9]+)*)\.sql$")
_IDENTIFIER = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")

# Any fixed number works; it only has to be the same for every AI layer process, so two
# instances starting together cannot apply the same migration twice.
_ADVISORY_LOCK_KEY = 7_110_411_902_516_001

_CREATE_HISTORY_TABLE = """
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    text        PRIMARY KEY,
    name       text        NOT NULL,
    checksum   text        NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now()
)
"""


class MigrationError(RuntimeError):
    """The index migrations cannot be applied safely."""


class InvalidMigrationFileError(MigrationError):
    """A file in the migrations folder breaks the naming rules."""


class MigrationChecksumMismatchError(MigrationError):
    """A migration that already ran has been edited since."""


@dataclass(frozen=True, slots=True)
class Migration:
    version: str
    name: str
    sql: str
    checksum: str


def discover_migrations(directory: Path = MIGRATIONS_DIR) -> list[Migration]:
    """Every ``.sql`` file in ``directory``, validated and sorted by version."""
    migrations: dict[str, Migration] = {}
    for path in sorted(directory.glob("*.sql")):
        match = _FILENAME.match(path.name)
        if match is None:
            raise InvalidMigrationFileError(
                f"{path.name}: migration files must be named NNNN_lower_snake_case.sql"
            )
        version = match["version"]
        if version in migrations:
            raise InvalidMigrationFileError(f"{path.name}: version {version} is used twice")
        raw = path.read_bytes()
        migrations[version] = Migration(
            version=version,
            name=match["name"],
            sql=raw.decode("utf-8"),
            checksum=hashlib.sha256(raw).hexdigest(),
        )
    return [migrations[version] for version in sorted(migrations)]


def quote_schema(schema: str) -> str:
    """Validate a schema name before it is placed in SQL (identifiers cannot be bound)."""
    if not _IDENTIFIER.match(schema):
        raise ValueError(f"Invalid schema name: {schema!r}")
    return f'"{schema}"'


async def apply_migrations(
    connection: asyncpg.Connection,
    *,
    schema: str,
    directory: Path = MIGRATIONS_DIR,
) -> list[str]:
    """Apply every pending migration into ``schema``. Returns the versions it applied."""
    migrations = discover_migrations(directory)
    await connection.execute(f"SET search_path TO {quote_schema(schema)}, public")
    await connection.execute("SELECT pg_advisory_lock($1)", _ADVISORY_LOCK_KEY)
    try:
        await connection.execute(_CREATE_HISTORY_TABLE)
        applied: dict[str, str] = {
            row["version"]: row["checksum"]
            for row in await connection.fetch("SELECT version, checksum FROM schema_migrations")
        }

        known_versions = {migration.version for migration in migrations}
        missing = sorted(set(applied) - known_versions)
        if missing:
            raise MigrationError(
                f"Schema {schema} has migration(s) {', '.join(missing)} that no file provides. "
                "The code is older than the database."
            )

        newly_applied: list[str] = []
        for migration in migrations:
            recorded = applied.get(migration.version)
            if recorded is not None:
                if recorded != migration.checksum:
                    raise MigrationChecksumMismatchError(
                        f"Migration {migration.version}_{migration.name} was edited after it "
                        "was applied. Put the change in a new migration file instead."
                    )
                continue

            async with connection.transaction():
                await connection.execute(migration.sql)
                await connection.execute(
                    "INSERT INTO schema_migrations (version, name, checksum) VALUES ($1, $2, $3)",
                    migration.version,
                    migration.name,
                    migration.checksum,
                )
            newly_applied.append(migration.version)
            log.info(
                "index_migration_applied",
                schema=schema,
                version=migration.version,
                name=migration.name,
            )
        return newly_applied
    finally:
        await connection.execute("SELECT pg_advisory_unlock($1)", _ADVISORY_LOCK_KEY)
