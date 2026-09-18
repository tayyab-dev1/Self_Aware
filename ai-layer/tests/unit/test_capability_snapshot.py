"""The committed metadata snapshot parses into the generated models and is consistent."""

from __future__ import annotations

import re

from app.capabilities.snapshot import load_snapshot


def test_the_snapshot_parses_into_the_generated_models() -> None:
    snapshot = load_snapshot()

    assert snapshot.capabilities, "the snapshot lists no capabilities"
    ids = [capability.id for capability in snapshot.capabilities]
    assert ids == sorted(ids)


def test_every_version_is_a_sha256() -> None:
    for capability in load_snapshot().capabilities:
        assert re.fullmatch(r"[0-9a-f]{64}", capability.version), capability.id


def test_siblings_are_registered_and_symmetric() -> None:
    capabilities = {capability.id: capability for capability in load_snapshot().capabilities}

    for capability in capabilities.values():
        for sibling_id in capability.disambiguate_from:
            assert sibling_id in capabilities, f"{capability.id} names unregistered {sibling_id}"
            assert capability.id in capabilities[sibling_id].disambiguate_from, (
                f"{capability.id} names {sibling_id}, which does not name it back"
            )


def test_no_capability_publishes_an_endpoint_address() -> None:
    for capability in load_snapshot().capabilities:
        assert "/api/" not in capability.model_dump_json(), capability.id
