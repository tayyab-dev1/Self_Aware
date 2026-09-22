"""Settings, read from ``AI_LAYER_*`` environment variables (see self_aware/.env.example)."""

from __future__ import annotations

import os
from collections.abc import Mapping
from functools import lru_cache
from typing import Literal, Self

from pydantic import Field, HttpUrl, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_PREFIX = "AI_LAYER_"


class Settings(BaseSettings):
    """Everything the AI layer can be configured with. Unknown keys are rejected."""

    model_config = SettingsConfigDict(env_prefix=ENV_PREFIX, extra="forbid", frozen=True)

    # HTTP server
    host: str = "127.0.0.1"
    port: int = Field(default=8081, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    log_format: Literal["console", "json"] = "console"

    # The backend's agent gateway: the only backend endpoints this service calls
    backend_base_url: HttpUrl = HttpUrl("http://127.0.0.1:8080")
    backend_timeout_seconds: float = Field(default=5.0, gt=0)

    # The capability index (schema "ai_layer"): the only database access this service has
    index_db_host: str = "localhost"
    index_db_port: int = Field(default=5433, ge=1, le=65535)
    index_db_name: str = "sms"
    index_db_user: str = "sms_ai_layer"
    index_db_password: SecretStr = SecretStr("ai_layer_dev_password")
    index_db_pool_min_size: int = Field(default=1, ge=0)
    index_db_pool_max_size: int = Field(default=5, ge=1)
    index_auto_migrate: bool = True

    # The embeddings service (docker compose profile "embeddings"): BGE-M3 over HTTP
    embeddings_base_url: HttpUrl = HttpUrl("http://127.0.0.1:8083")
    embeddings_timeout_seconds: float = Field(default=60.0, gt=0)

    # Metadata sync: poll GET /agent/metadata/versions and re-embed what changed
    metadata_sync_enabled: bool = True
    metadata_sync_interval_seconds: float = Field(default=30.0, gt=0)

    # Hybrid retrieval: how many candidates the planner sees, and how the two searches are fused
    retrieval_candidate_cap: int = Field(default=30, ge=1)
    retrieval_rrf_k: int = Field(default=60, ge=1)
    retrieval_branch_limit: int = Field(default=50, ge=1)

    # Model calls go to the Gemini API. Empty key: chat is off, and readiness says why.
    gemini_api_key: SecretStr = SecretStr("")
    model_timeout_seconds: float = Field(default=120.0, gt=0)
    plan_max_steps: int = Field(default=3, ge=1, le=3)

    # The capability chooser (on by default, decision 75): TypeSafe's Jev picks which candidates the
    # planner sees. Without a key it stays off even when enabled, and chat plans as before.
    chooser_enabled: bool = True
    typesafe_api_key: SecretStr = SecretStr("")
    typesafe_base_url: HttpUrl = HttpUrl("https://api.typesafe.ai")
    chooser_timeout_seconds: float = Field(default=15.0, gt=0)

    # Chat: how long an unanswered conversation is kept, and how many plans are remembered
    chat_session_ttl_seconds: float = Field(default=1800.0, gt=0)
    plan_cache_size: int = Field(default=256, ge=0)
    # GET / serves a small page for trying POST /chat by hand (a developer tool)
    chat_page_enabled: bool = True
    # POST /chat with "trace": true also returns every pipeline stage's input and output (for demos)
    chat_trace_enabled: bool = True

    @model_validator(mode="after")
    def _pool_bounds_are_ordered(self) -> Self:
        if self.index_db_pool_min_size > self.index_db_pool_max_size:
            raise ValueError("index_db_pool_min_size must not exceed index_db_pool_max_size")
        return self


class UnknownSettingError(ValueError):
    """An ``AI_LAYER_*`` variable is set that no setting reads, usually a typo."""


def reject_unknown_environment(environ: Mapping[str, str] | None = None) -> None:
    """Fail loudly on ``AI_LAYER_*`` variables that would otherwise be silently ignored."""
    env = os.environ if environ is None else environ
    known = {ENV_PREFIX + name.upper() for name in Settings.model_fields}
    unknown = sorted(
        key for key in env if key.upper().startswith(ENV_PREFIX) and key.upper() not in known
    )
    if unknown:
        raise UnknownSettingError(f"Unknown AI layer setting(s): {', '.join(unknown)}")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """The process-wide settings, validated once."""
    reject_unknown_environment()
    return Settings()
