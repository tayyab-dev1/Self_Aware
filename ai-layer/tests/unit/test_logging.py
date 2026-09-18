from __future__ import annotations

from typing import Any

import pytest
import structlog

from app.core.logging import REDACTED, configure_logging, is_sensitive_key, redact_sensitive


def redact(event: dict[str, Any]) -> dict[str, Any]:
    return dict(redact_sensitive(None, "info", dict(event)))


def test_redacts_sensitive_keys_at_any_depth() -> None:
    result = redact(
        {
            "event": "calling backend",
            "user_token": "abc.def",
            "request": {"headers": {"Authorization": "Bearer abc.def"}, "path": "/chat"},
            "attempts": [{"password": "p4ss"}],
        }
    )

    assert result["user_token"] == REDACTED
    assert result["request"]["headers"]["Authorization"] == REDACTED
    assert result["request"]["path"] == "/chat"
    assert result["attempts"][0]["password"] == REDACTED
    assert result["event"] == "calling backend"


@pytest.mark.parametrize(
    "key", ["user_token", "preflight_token", "Authorization", "x-api-key", "db_password"]
)
def test_sensitive_key_names(key: str) -> None:
    assert is_sensitive_key(key)


@pytest.mark.parametrize(
    "key", ["input_tokens", "tokens_used", "plan_id", "session_id", "capability_id"]
)
def test_ordinary_key_names_are_left_alone(key: str) -> None:
    assert not is_sensitive_key(key)


def test_rendered_log_line_never_contains_the_secret(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("INFO", "json")

    structlog.get_logger("test").info("chat_received", user_token="s3cr3t-value", plan_id="pl_1")

    output = capsys.readouterr().out
    assert "s3cr3t-value" not in output
    assert "pl_1" in output
    assert REDACTED in output
