"""The committed snapshot of `GET /agent/metadata`: self_aware/snapshots/agent-metadata.json.

The backend writes it and fails its own build when it is stale, so the AI layer can check capability
metadata without a running backend: in tests, in build checks, and later in evaluation runs.
"""

from __future__ import annotations

from pathlib import Path

from app.gateway.models import AgentMetadataResponse

SNAPSHOT_PATH = Path(__file__).resolve().parents[3] / "snapshots" / "agent-metadata.json"


def load_snapshot(path: Path = SNAPSHOT_PATH) -> AgentMetadataResponse:
    return AgentMetadataResponse.model_validate_json(path.read_bytes())
