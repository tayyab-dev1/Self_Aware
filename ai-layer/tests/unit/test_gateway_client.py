from __future__ import annotations

import json
from collections.abc import Callable
from contextlib import aclosing
from typing import Any

import httpx
import pytest

from app.capabilities.snapshot import SNAPSHOT_PATH
from app.gateway.client import GatewayClient
from app.gateway.errors import GatewayProtocolError, GatewayRejectedError, GatewayUnavailableError
from app.gateway.models import ParamValue, Plan, PlanStep

Handler = Callable[[httpx.Request], httpx.Response]


def gateway(handler: Handler) -> GatewayClient:
    transport = httpx.MockTransport(handler)
    return GatewayClient(httpx.AsyncClient(base_url="http://backend.test", transport=transport))


def real_metadata() -> dict[str, Any]:
    """What the backend actually serves, from the snapshot its own build keeps current."""
    body: dict[str, Any] = json.loads(SNAPSHOT_PATH.read_text())
    return body


async def test_metadata_is_parsed_into_the_generated_models() -> None:
    body = real_metadata()

    async with aclosing(gateway(lambda _: httpx.Response(200, json=body))) as client:
        metadata = await client.get_metadata()

    ids = [capability.id for capability in metadata.capabilities]
    assert ids == [capability["id"] for capability in body["capabilities"]]
    assert "fee.reminder.send" in ids


async def test_metadata_comes_from_the_gateway_path() -> None:
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(request.url.path)
        return httpx.Response(200, json={"capabilities": []})

    async with aclosing(gateway(handler)) as client:
        await client.get_metadata()

    assert requested == ["/agent/metadata"]


async def test_an_unknown_field_from_the_backend_is_a_contract_violation() -> None:
    body = real_metadata()
    body["capabilities"][0]["url"] = "/api/v1/fee-overdue"

    async with aclosing(gateway(lambda _: httpx.Response(200, json=body))) as client:
        with pytest.raises(GatewayProtocolError, match="does not match the contract"):
            await client.get_metadata()


async def test_a_missing_required_field_is_a_contract_violation() -> None:
    body = real_metadata()
    del body["capabilities"][0]["version"]

    async with aclosing(gateway(lambda _: httpx.Response(200, json=body))) as client:
        with pytest.raises(GatewayProtocolError):
            await client.get_metadata()


async def test_versions_are_parsed() -> None:
    body = {"versions": [{"id": "fee.reminder.send", "version": "ab" * 32}]}

    async with aclosing(gateway(lambda _: httpx.Response(200, json=body))) as client:
        versions = await client.get_versions()

    assert versions.versions[0].id == "fee.reminder.send"


async def test_session_capabilities_send_the_user_token_as_a_bearer_credential() -> None:
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("Authorization"))
        return httpx.Response(200, json={"user_id": "1", "capability_ids": ["fee.overdue.list"]})

    async with aclosing(gateway(handler)) as client:
        allowed = await client.get_session_capabilities("token-123")

    assert seen == ["Bearer token-123"]
    assert allowed.capability_ids == ["fee.overdue.list"]


async def test_an_error_code_from_the_backend_is_a_rejection() -> None:
    error = {"code": "UNAUTHENTICATED", "message": "A valid user credential is required"}

    async with aclosing(gateway(lambda _: httpx.Response(401, json=error))) as client:
        with pytest.raises(GatewayRejectedError) as raised:
            await client.get_session_capabilities("wrong")

    assert raised.value.code == "UNAUTHENTICATED"
    assert raised.value.status == 401


async def test_an_error_status_without_an_error_body_is_a_protocol_error() -> None:
    async with aclosing(
        gateway(lambda _: httpx.Response(503, text="Service Unavailable"))
    ) as client:
        with pytest.raises(GatewayProtocolError, match="HTTP 503"):
            await client.get_metadata()


async def test_an_unreachable_backend_is_unavailable() -> None:
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with aclosing(gateway(refuse)) as client:
        with pytest.raises(GatewayUnavailableError, match="Could not reach"):
            await client.get_metadata()


async def test_a_slow_backend_is_unavailable() -> None:
    def stall(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    async with aclosing(gateway(stall)) as client:
        with pytest.raises(GatewayUnavailableError, match="Timed out"):
            await client.get_metadata()


def reminder_plan() -> Plan:
    """A plan for the seeded school's reminder, as the planner will produce it (Phase 5)."""
    return Plan(
        plan_id="plan-1",
        session_id="session-1",
        steps=[
            PlanStep(
                step=1,
                capability_id="fee.reminder.send",
                capability_version="ab" * 32,
                params={
                    "section_id": ParamValue(raw="class 5 blue"),
                    "channel": ParamValue(value="whatsapp"),
                },
            )
        ],
    )


PREFLIGHT_OK: dict[str, Any] = {
    "plan_id": "plan-1",
    "requires_confirmation": True,
    "confirmation": (
        "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, "
        "covering PKR 71,500 outstanding. This cannot be undone."
    ),
    "warnings": ["This cannot be undone."],
    "steps": [
        {
            "step": 1,
            "capability_id": "fee.reminder.send",
            "pending": False,
            "resolved": [{"param": "section_id", "id": "2", "label": "Class 5 Blue"}],
            "count": 5,
            "unit": "guardians",
            "line": "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, "
            "covering PKR 71,500 outstanding.",
        }
    ],
    "token": "eyJwbGFuIjoxfQ.c2lnbmF0dXJl",
    "expires_at": "2026-09-14T09:05:00Z",
}


async def test_preflight_posts_the_plan_without_empty_fields_and_the_user_token() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=PREFLIGHT_OK)

    async with aclosing(gateway(handler)) as client:
        await client.preflight(reminder_plan(), "token-123")

    request = seen[0]
    assert (request.method, request.url.path) == ("POST", "/agent/preflight")
    assert request.headers["Authorization"] == "Bearer token-123"
    # Absent forms are left out, not sent as null: the backend hashes exactly this plan.
    assert json.loads(request.content) == {
        "plan": {
            "plan_id": "plan-1",
            "session_id": "session-1",
            "steps": [
                {
                    "step": 1,
                    "capability_id": "fee.reminder.send",
                    "capability_version": "ab" * 32,
                    "params": {
                        "section_id": {"raw": "class 5 blue"},
                        "channel": {"value": "whatsapp"},
                    },
                }
            ],
        }
    }


async def test_preflight_returns_the_confirmation_the_backend_wrote() -> None:
    async with aclosing(gateway(lambda _: httpx.Response(200, json=PREFLIGHT_OK))) as client:
        response = await client.preflight(reminder_plan(), "token-123")

    assert response.confirmation == PREFLIGHT_OK["confirmation"]
    assert response.steps[0].resolved[0].label == "Class 5 Blue"
    assert response.steps[0].count == 5
    assert response.expires_at.isoformat() == "2026-09-14T09:05:00+00:00"


async def test_an_ambiguous_name_is_a_rejection_carrying_the_candidates_to_ask_about() -> None:
    error = {
        "code": "AMBIGUOUS_ENTITY",
        "message": 'Step 1: "class 5" matches 2 section records',
        "step": 1,
        "param": "section_id",
        "candidates": [
            {"id": "2", "label": "Class 5 Blue", "context": "12 students"},
            {"id": "1", "label": "Class 5 Green", "context": "10 students"},
        ],
    }

    async with aclosing(gateway(lambda _: httpx.Response(422, json=error))) as client:
        with pytest.raises(GatewayRejectedError) as raised:
            await client.preflight(reminder_plan(), "token-123")

    assert raised.value.code == "AMBIGUOUS_ENTITY"
    assert raised.value.error.param == "section_id"
    candidates = raised.value.error.candidates or []
    assert [candidate.label for candidate in candidates] == ["Class 5 Blue", "Class 5 Green"]


async def test_a_failed_precondition_is_a_rejection_carrying_its_hint() -> None:
    error = {
        "code": "PRECONDITION_FAILED",
        "message": "Nobody in this section has overdue fees right now",
        "step": 1,
        "precondition": "section_has_defaulters",
        "hint": "Nobody in this section has overdue fees right now",
    }

    async with aclosing(gateway(lambda _: httpx.Response(422, json=error))) as client:
        with pytest.raises(GatewayRejectedError) as raised:
            await client.preflight(reminder_plan(), "token-123")

    assert raised.value.status == 422
    assert raised.value.error.hint == "Nobody in this section has overdue fees right now"


EXECUTE_OK: dict[str, Any] = {
    "plan_id": "plan-1",
    "outcome": "partial",
    "steps": [
        {
            "step": 1,
            "capability_id": "fee.payment.record",
            "status": "succeeded",
            "reply": "Recorded PKR 5,000 against Ahmed Raza's September 2026 invoice "
            "INV/LHR/26-27/000031. Receipt RCT/LHR/26-27/000047.",
            "count": 1,
            "data": {"receipt_number": "RCT/LHR/26-27/000047", "outstanding_balance": 4000.00},
        },
        {
            "step": 2,
            "capability_id": "fee.reminder.send",
            "status": "failed",
            "error": {
                "code": "COUNT_CHANGED",
                "message": "Step 2: the confirmation said 5 guardians, but it would now be 6",
                "step": 2,
                "confirmed_count": 5,
                "current_count": 6,
            },
        },
    ],
}


async def test_execute_posts_the_confirmed_plan_token_and_sentence() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=EXECUTE_OK)

    async with aclosing(gateway(handler)) as client:
        await client.execute(
            reminder_plan(), "preflight-token", "remind class 5 blue", "user-token"
        )

    request = seen[0]
    body = json.loads(request.content)
    assert (request.method, request.url.path) == ("POST", "/agent/execute")
    assert request.headers["Authorization"] == "Bearer user-token"
    assert body["token"] == "preflight-token"
    assert body["sentence"] == "remind class 5 blue"
    assert body["plan"]["steps"][0]["params"]["section_id"] == {"raw": "class 5 blue"}


async def test_execute_reports_each_step_including_one_that_failed() -> None:
    async with aclosing(gateway(lambda _: httpx.Response(200, json=EXECUTE_OK))) as client:
        response = await client.execute(reminder_plan(), "t", "s", "u")

    assert response.outcome == "partial"
    assert [step.status for step in response.steps] == ["succeeded", "failed"]
    failed = response.steps[1].error
    assert failed is not None
    assert (failed.code, failed.confirmed_count, failed.current_count) == ("COUNT_CHANGED", 5, 6)


async def test_a_token_refused_for_the_whole_plan_is_a_rejection() -> None:
    error = {"code": "TOKEN_EXPIRED", "message": "The token expired; run preflight again"}

    async with aclosing(gateway(lambda _: httpx.Response(409, json=error))) as client:
        with pytest.raises(GatewayRejectedError) as raised:
            await client.execute(reminder_plan(), "t", "s", "u")

    assert raised.value.code == "TOKEN_EXPIRED"
