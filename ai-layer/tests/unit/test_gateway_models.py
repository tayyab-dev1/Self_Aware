"""The AI layer's copy of the gateway contract comes from the backend, never from a hand edit."""

from __future__ import annotations

import inspect
from pathlib import Path

from pydantic import BaseModel

from app.gateway import models
from scripts.generate_gateway_models import MODELS_PATH, SPEC_PATH, generate


def test_models_are_generated_from_the_committed_contract(tmp_path: Path) -> None:
    regenerated = tmp_path / "models.py"

    generate(output=regenerated, spec=SPEC_PATH)

    assert regenerated.read_text() == MODELS_PATH.read_text(), (
        "app/gateway/models.py is out of date with openapi/agent-gateway.json. "
        "Run `make contracts` instead of editing it by hand."
    )


def test_every_generated_model_rejects_unknown_fields() -> None:
    generated = [
        cls
        for _, cls in inspect.getmembers(models, inspect.isclass)
        if issubclass(cls, BaseModel) and cls.__module__ == models.__name__
    ]

    assert generated, "no models were generated"
    for cls in generated:
        assert cls.model_config.get("extra") == "forbid", cls.__name__
