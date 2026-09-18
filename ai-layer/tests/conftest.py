"""Shared test setup for every AI layer test."""

from __future__ import annotations

import os
from collections.abc import Iterator

import pytest

from app.core.settings import ENV_PREFIX, get_settings


@pytest.fixture(autouse=True)
def isolated_settings_environment(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Tests never see the developer's ``AI_LAYER_*`` variables (the Makefile exports .env)."""
    for key in list(os.environ):
        if key.upper().startswith(ENV_PREFIX):
            monkeypatch.delenv(key)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.hookimpl(tryfirst=True)
def pytest_collection_modifyitems(items: list[pytest.Item]) -> None:
    """Mark tests by what they need, from the folder they live in.

    tests/integration  needs Docker (a throwaway Postgres)          -> marker "integration"
    tests/build_checks needs the embeddings service (embeddings-up) -> marker "embeddings"
    tests/model_checks calls the real models through the Claude CLI -> marker "model"
    """
    for item in items:
        if "integration" in item.path.parts:
            item.add_marker(pytest.mark.integration)
        if "build_checks" in item.path.parts:
            item.add_marker(pytest.mark.embeddings)
        if "model_checks" in item.path.parts:
            item.add_marker(pytest.mark.model)
