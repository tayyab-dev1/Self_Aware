from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import SecretStr, ValidationError

from app.core.settings import (
    ENV_PREFIX,
    Settings,
    UnknownSettingError,
    get_settings,
    reject_unknown_environment,
)

ENV_EXAMPLE = Path(__file__).resolve().parents[3] / ".env.example"


def test_defaults_match_the_documented_local_setup() -> None:
    settings = Settings()

    assert settings.port == 8081
    assert str(settings.backend_base_url) == "http://127.0.0.1:8080/"
    assert settings.index_db_user == "sms_ai_layer"
    assert settings.index_db_port == 5433


def test_env_example_documents_exactly_the_real_settings() -> None:
    keys = [
        line.split("=", 1)[0]
        for line in ENV_EXAMPLE.read_text().splitlines()
        if line.startswith(ENV_PREFIX)
    ]

    documented = {key.removeprefix(ENV_PREFIX).lower() for key in keys}

    assert documented == set(Settings.model_fields)


def test_environment_variables_override_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_LAYER_PORT", "9000")
    monkeypatch.setenv("AI_LAYER_BACKEND_BASE_URL", "http://backend.internal:8090")

    settings = get_settings()

    assert settings.port == 9000
    assert settings.backend_base_url.port == 8090


def test_a_misspelled_setting_fails_loudly(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_LAYER_BACKEND_URL", "http://typo")

    with pytest.raises(UnknownSettingError, match="AI_LAYER_BACKEND_URL"):
        get_settings()


def test_known_settings_pass_the_unknown_check() -> None:
    reject_unknown_environment({"AI_LAYER_PORT": "8081", "PATH": "/usr/bin"})


def test_unexpected_constructor_field_is_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(not_a_setting=1)  # type: ignore[call-arg]


def test_pool_bounds_must_be_ordered() -> None:
    with pytest.raises(ValidationError, match="must not exceed"):
        Settings(index_db_pool_min_size=6, index_db_pool_max_size=5)


def test_the_database_password_never_shows_up_in_output() -> None:
    settings = Settings(index_db_password=SecretStr("hunter2-not-for-logs"))

    assert "hunter2-not-for-logs" not in repr(settings)
    assert "hunter2-not-for-logs" not in str(settings.model_dump())
