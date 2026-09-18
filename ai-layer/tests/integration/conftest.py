"""A throwaway Postgres + pgvector container, set up by the same init script as docker compose.

It is started once per test session. Tests connect as the real, least-privileged AI layer role,
so a privilege mistake in the init script fails here before it reaches anyone's machine.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import asyncpg
import pytest
import pytest_asyncio
from pydantic import SecretStr
from testcontainers.core.container import DockerContainer

from app.core.settings import Settings
from app.index.database import IndexDatabase

IMAGE = "pgvector/pgvector:0.8.6-pg16"
INIT_SCRIPTS = Path(__file__).resolve().parents[3] / "docker" / "postgres" / "initdb"


@dataclass(frozen=True, slots=True)
class PostgresServer:
    host: str
    port: int
    database: str = "sms"
    superuser: str = "postgres"
    superuser_password: str = "postgres_test_password"
    backend_user: str = "sms_backend"
    backend_password: str = "backend_test_password"
    ai_user: str = "sms_ai_layer"
    ai_password: str = "ai_layer_test_password"

    def ai_settings(self, **overrides: Any) -> Settings:
        values: dict[str, Any] = {
            "index_db_host": self.host,
            "index_db_port": self.port,
            "index_db_name": self.database,
            "index_db_user": self.ai_user,
            "index_db_password": SecretStr(self.ai_password),
            **overrides,
        }
        return Settings(**values)

    async def connect(self, user: str, password: str) -> asyncpg.Connection:
        return await asyncpg.connect(
            host=self.host, port=self.port, database=self.database, user=user, password=password
        )


@pytest.fixture(scope="session")
def postgres_server() -> Iterator[PostgresServer]:
    defaults = PostgresServer(host="", port=0)
    container = (
        DockerContainer(IMAGE)
        .with_env("POSTGRES_DB", defaults.database)
        .with_env("POSTGRES_USER", defaults.superuser)
        .with_env("POSTGRES_PASSWORD", defaults.superuser_password)
        .with_env("BACKEND_DB_USER", defaults.backend_user)
        .with_env("BACKEND_DB_PASSWORD", defaults.backend_password)
        .with_env("AI_LAYER_INDEX_DB_USER", defaults.ai_user)
        .with_env("AI_LAYER_INDEX_DB_PASSWORD", defaults.ai_password)
        .with_volume_mapping(INIT_SCRIPTS, "/docker-entrypoint-initdb.d", "ro")
        .with_exposed_ports(5432)
    )
    container.start()
    try:
        _wait_until_initialized(container, defaults)
        yield PostgresServer(
            host=container.get_container_host_ip(), port=container.get_exposed_port(5432)
        )
    finally:
        container.stop()


def _wait_until_initialized(container: DockerContainer, server: PostgresServer) -> None:
    """TCP on 127.0.0.1 inside the container only answers after the init script has run."""
    command = ["pg_isready", "-h", "127.0.0.1", "-U", server.superuser, "-d", server.database]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if container.exec(command).exit_code == 0:
            return
        time.sleep(0.5)
    raise TimeoutError("Postgres test container did not finish initialising within 120s")


@pytest_asyncio.fixture
async def superuser_connection(
    postgres_server: PostgresServer,
) -> AsyncIterator[asyncpg.Connection]:
    connection = await postgres_server.connect(
        postgres_server.superuser, postgres_server.superuser_password
    )
    try:
        yield connection
    finally:
        await connection.close()


@pytest_asyncio.fixture
async def ai_connection(postgres_server: PostgresServer) -> AsyncIterator[asyncpg.Connection]:
    connection = await postgres_server.connect(postgres_server.ai_user, postgres_server.ai_password)
    try:
        yield connection
    finally:
        await connection.close()


@pytest_asyncio.fixture
async def scratch_index(
    postgres_server: PostgresServer, superuser_connection: asyncpg.Connection
) -> AsyncIterator[IndexDatabase]:
    """An IndexDatabase on a brand-new schema owned by the AI layer role, dropped afterwards."""
    schema = f"ai_test_{uuid.uuid4().hex[:12]}"
    await superuser_connection.execute(
        f'CREATE SCHEMA "{schema}" AUTHORIZATION "{postgres_server.ai_user}"'
    )
    database = await IndexDatabase.connect(postgres_server.ai_settings(), schema=schema)
    try:
        yield database
    finally:
        await database.close()
        await superuser_connection.execute(f'DROP SCHEMA "{schema}" CASCADE')
