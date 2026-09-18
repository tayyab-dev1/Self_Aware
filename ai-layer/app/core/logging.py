"""Structured logging with structlog. Never ``print``, and never log a token or password.

Anything bound with ``structlog.contextvars.bind_contextvars`` (``request_id`` today;
``plan_id`` and ``session_id`` once plans exist) is added to every line automatically.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, MutableMapping
from typing import Any

import structlog
from structlog.typing import Processor, WrappedLogger

REDACTED = "[REDACTED]"

# A key is sensitive when its name ends with one of these (after lower-casing and
# turning "-" into "_"). "user_token" is redacted; "input_tokens" is not.
_SENSITIVE_SUFFIXES = (
    "token",
    "password",
    "secret",
    "authorization",
    "api_key",
    "apikey",
    "cookie",
    "dsn",
)


def is_sensitive_key(key: object) -> bool:
    if not isinstance(key, str):
        return False
    normalized = key.lower().replace("-", "_")
    return normalized.endswith(_SENSITIVE_SUFFIXES)


def _redact(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {k: REDACTED if is_sensitive_key(k) else _redact(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return type(value)(_redact(item) for item in value)
    return value


def redact_sensitive(
    _logger: WrappedLogger, _method_name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """structlog processor: replace the value of every sensitive key, at any depth."""
    for key, value in list(event_dict.items()):
        event_dict[key] = REDACTED if is_sensitive_key(key) else _redact(value)
    return event_dict


def configure_logging(level: str = "INFO", log_format: str = "console") -> None:
    """Configure structlog for the whole process. Safe to call more than once."""
    renderer: list[Processor]
    if log_format == "json":
        renderer = [structlog.processors.dict_tracebacks, structlog.processors.JSONRenderer()]
    else:
        renderer = [structlog.dev.ConsoleRenderer()]

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            redact_sensitive,
            structlog.processors.StackInfoRenderer(),
            *renderer,
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelName(level)),
        cache_logger_on_first_use=False,
    )
