"""The chat state machine, with a scripted backend and a scripted planner."""

from __future__ import annotations

from collections.abc import Collection
from datetime import UTC, date, datetime
from typing import Any

import pytest

from app.capabilities.snapshot import load_snapshot
from app.core import trace
from app.decompose.decomposer import Decomposition, Intent
from app.gateway.errors import GatewayRejectedError
from app.gateway.models import (
    AgentErrorResponse,
    EntityCandidate,
    ExecutedStep,
    ExecuteOutcome,
    ExecuteResponse,
    ParamValue,
    Plan,
    PlanStep,
    PreflightResponse,
    SessionCapabilitiesResponse,
    StepStatus,
)
from app.llm.runner import ModelOutputError
from app.orchestration.orchestrator import ChatOrchestrator, ChatResult, ChatTurn
from app.orchestration.plan_cache import PlanCache
from app.orchestration.session import InMemorySessionStore, Phase
from app.planning.outcomes import NeedsInput, PlannedSteps, PlanOutcome, Refusal, RefusalReason
from app.planning.service import Understanding

CATALOG = {capability.id: capability for capability in load_snapshot().capabilities}
TODAY = date(2026, 9, 14)


def version(capability_id: str) -> str:
    return CATALOG[capability_id].version


def step(capability_id: str, number: int = 1, **params: ParamValue) -> PlanStep:
    return PlanStep(
        step=number, capability_id=capability_id, capability_version=version(capability_id),
        params=params,
    )  # fmt: skip


REMINDER = step(
    "fee.reminder.send", section_id=ParamValue(raw="class 5"), channel=ParamValue(value="sms")
)
OVERDUE = step(
    "fee.overdue.list", scope=ParamValue(value="section"), section_id=ParamValue(raw="class 5 blue")
)


def planned(*steps: PlanStep) -> PlannedSteps:
    return PlannedSteps(Plan(plan_id="from-planner", session_id="from-planner", steps=list(steps)))


def preflight_ok(*, confirmation: str | None, needs_confirming: bool = True) -> PreflightResponse:
    return PreflightResponse(
        confirmation=confirmation,
        expires_at=datetime(2026, 9, 14, 9, 5, tzinfo=UTC),
        plan_id="ignored",
        requires_confirmation=needs_confirming,
        steps=[],
        token=f"token-{confirmation}",
        warnings=[],
    )


def rejected(code: str, status: int = 422, **fields: Any) -> GatewayRejectedError:
    message = fields.pop("message", code.lower())
    return GatewayRejectedError(status, AgentErrorResponse(code=code, message=message, **fields))


def executed(
    reply: str, status: StepStatus = StepStatus.succeeded, **fields: Any
) -> ExecuteResponse:
    ran = ExecutedStep(
        step=1, capability_id="fee.reminder.send", status=status, reply=reply, **fields
    )
    completed = status is StepStatus.succeeded
    return ExecuteResponse(
        outcome=ExecuteOutcome.completed if completed else ExecuteOutcome.failed,
        plan_id="p",
        steps=[ran],
    )


class Backend:
    def __init__(self) -> None:
        self.user_id = "7"
        self.preflights: list[PreflightResponse | GatewayRejectedError] = []
        self.executions: list[ExecuteResponse | GatewayRejectedError] = []
        self.preflighted: list[Plan] = []
        self.executed: list[tuple[Plan, str, str]] = []

    async def get_session_capabilities(self, user_token: str) -> SessionCapabilitiesResponse:
        assert user_token == "user-token"
        return SessionCapabilitiesResponse(user_id=self.user_id, capability_ids=list(CATALOG))

    async def preflight(self, plan: Plan, user_token: str) -> PreflightResponse:
        self.preflighted.append(plan.model_copy(deep=True))
        answer = self.preflights.pop(0)
        if isinstance(answer, GatewayRejectedError):
            raise answer
        return answer

    async def execute(
        self, plan: Plan, token: str, sentence: str, user_token: str
    ) -> ExecuteResponse:
        self.executed.append((plan.model_copy(deep=True), token, sentence))
        answer = self.executions.pop(0)
        if isinstance(answer, GatewayRejectedError):
            raise answer
        return answer


class Planner:
    def __init__(self, *outcomes: PlanOutcome | Exception) -> None:
        self.outcomes = list(outcomes)
        self.calls = 0

    async def understand(
        self, sentence: str, *, allowed: Collection[str], session_id: str
    ) -> Understanding:
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return Understanding(Decomposition((Intent("an intent", ()),)), tuple(CATALOG), outcome)


class Chat:
    def __init__(self, planner: Planner, cache_size: int = 16) -> None:
        self.backend = Backend()
        self.planner = planner
        self.sessions = InMemorySessionStore(ttl_seconds=60)
        self.orchestrator = ChatOrchestrator(
            gateway=self.backend, planner=planner, catalog=lambda: CATALOG,
            sessions=self.sessions, plan_cache=PlanCache(cache_size), today=lambda: TODAY,
        )  # fmt: skip
        self.session_id: str | None = None

    async def say(self, **turn: Any) -> ChatResult:
        result = await self.orchestrator.handle(self.session_id, ChatTurn(**turn), "user-token")
        self.session_id = result.session_id
        return result

    @property
    def phase(self) -> Phase:
        assert self.session_id is not None
        session = self.sessions.get(self.session_id)
        assert session is not None
        return session.phase


async def test_a_read_runs_straight_after_preflight_and_answers_with_the_backends_reply() -> None:
    chat = Chat(Planner(planned(OVERDUE)))
    chat.backend.preflights = [preflight_ok(confirmation=None, needs_confirming=False)]
    chat.backend.executions = [executed("Overdue fees for Class 5 Blue: 8 students.")]

    result = await chat.say(message="class 5 blue ka baqaya dikhao")

    assert result.reply.type == "answer"
    assert result.reply.text == "Overdue fees for Class 5 Blue: 8 students."
    assert result.reply.steps[0].status == "succeeded"
    plan, token, sentence = chat.backend.executed[0]
    assert (token, sentence) == ("token-None", "class 5 blue ka baqaya dikhao")
    assert plan.session_id == result.session_id and plan.plan_id != "from-planner"
    assert chat.phase is Phase.IDLE


async def test_a_write_waits_for_yes_then_runs_the_confirmed_plan() -> None:
    chat = Chat(
        Planner(planned(step("fee.reminder.send", section_id=ParamValue(raw="class 5 blue"))))
    )
    chat.backend.preflights = [preflight_ok(confirmation="Send a fee reminder to 5 guardians.")]
    chat.backend.executions = [executed("Sent a fee reminder to 5 guardians.")]

    asked = await chat.say(message="class 5 blue ko reminder bhejo")
    assert asked.reply.type == "confirmation"
    assert asked.reply.text == "Send a fee reminder to 5 guardians."
    assert chat.phase is Phase.AWAITING_CONFIRM
    assert chat.backend.executed == []

    done = await chat.say(confirm=True)

    assert done.reply.type == "answer"
    assert done.reply.text == "Sent a fee reminder to 5 guardians."
    assert chat.backend.executed[0][0].plan_id == asked.reply.plan_id
    assert chat.planner.calls == 1


async def test_an_ambiguous_name_is_asked_then_resumed_without_planning_again() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    options = [
        EntityCandidate(id="2", label="Class 5 Blue", context="12 students"),
        EntityCandidate(id="3", label="Class 5 Green", context="10 students"),
    ]
    chat.backend.preflights = [
        rejected("AMBIGUOUS_ENTITY", step=1, param="section_id", candidates=options),
        preflight_ok(confirmation="Send a fee reminder to 6 guardians in Class 5 Blue by SMS."),
    ]

    question = await chat.say(message="class 5 ko sms reminder bhejo")
    assert question.reply.type == "question"
    assert [option.label for option in question.reply.options] == ["Class 5 Blue", "Class 5 Green"]
    assert chat.phase is Phase.AWAITING_INPUT

    confirmation = await chat.say(message="1")

    assert confirmation.reply.type == "confirmation"
    resumed = chat.backend.preflighted[1]
    assert resumed.plan_id == chat.backend.preflighted[0].plan_id
    assert resumed.steps[0].params["section_id"] == ParamValue(raw="class 5", chosen_id="2")
    assert chat.planner.calls == 1


async def test_a_choice_that_is_not_an_option_is_asked_again() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    options = [
        EntityCandidate(id="2", label="Class 5 Blue"),
        EntityCandidate(id="3", label="Class 5 Green"),
    ]
    chat.backend.preflights = [
        rejected("AMBIGUOUS_ENTITY", step=1, param="section_id", candidates=options)
    ]
    await chat.say(message="class 5 ko sms reminder bhejo")

    again = await chat.say(choice="99")

    assert again.reply.type == "question"
    assert again.reply.text.startswith("Please pick one of the options")
    assert len(chat.backend.preflighted) == 1


async def test_a_missing_value_is_asked_for_and_filled_into_the_same_plan() -> None:
    partial = step(
        "fee.payment.record",
        invoice_id=ParamValue(raw="Ahmed Raza's September invoice"),
        route=ParamValue(value="cash"),
        payment_date=ParamValue(value="2026-09-14"),
    )
    chat = Chat(Planner(NeedsInput(partial, ("amount_received",))))
    chat.backend.preflights = [preflight_ok(confirmation="Record PKR 5,000 received in cash.")]

    question = await chat.say(message="Ahmed Raza ki September fees cash mein aayi")
    assert question.reply.type == "question"
    assert question.reply.text.startswith("I need one more detail. Exactly the amount received")

    unreadable = await chat.say(message="the usual amount")
    assert unreadable.reply.text.startswith("I couldn't read that.")

    confirmation = await chat.say(message="PKR 5,000")

    assert confirmation.reply.type == "confirmation"
    assert chat.backend.preflighted[0].steps[0].params["amount_received"] == ParamValue(
        value="5000"
    )
    assert chat.planner.calls == 1


@pytest.mark.parametrize(
    "cancel", [{"confirm": False}, {"message": "nahi"}, {"message": "Cancel."}]
)
async def test_cancelling_at_the_confirmation_runs_nothing(cancel: dict[str, Any]) -> None:
    chat = Chat(Planner(planned(REMINDER)))
    chat.backend.preflights = [preflight_ok(confirmation="Send a fee reminder to 6 guardians.")]
    await chat.say(message="class 5 ko sms reminder bhejo")

    result = await chat.say(**cancel)

    assert result.reply.type == "answer"
    assert result.reply.text == "Cancelled. Nothing was changed."
    assert chat.backend.executed == []
    assert chat.phase is Phase.IDLE


async def test_an_expired_confirmation_is_shown_again_from_the_same_plan() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    chat.backend.preflights = [
        preflight_ok(confirmation="Send a fee reminder to 6 guardians."),
        preflight_ok(confirmation="Send a fee reminder to 6 guardians."),
    ]
    chat.backend.executions = [rejected("TOKEN_EXPIRED", status=409)]
    await chat.say(message="class 5 ko sms reminder bhejo")

    again = await chat.say(confirm=True)

    assert again.reply.type == "confirmation"
    assert again.reply.text.startswith("That confirmation expired, so here it is again.")
    assert chat.phase is Phase.AWAITING_CONFIRM
    assert chat.planner.calls == 1


async def test_a_count_that_moved_is_confirmed_again_with_the_new_numbers() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    chat.backend.preflights = [
        preflight_ok(confirmation="Send a fee reminder to 5 guardians."),
        preflight_ok(confirmation="Send a fee reminder to 6 guardians."),
    ]
    moved = AgentErrorResponse(
        code="COUNT_CHANGED", message="moved", confirmed_count=5, current_count=6
    )
    chat.backend.executions = [executed("", StepStatus.failed, error=moved)]
    await chat.say(message="class 5 ko sms reminder bhejo")

    again = await chat.say(message="haan")

    assert again.reply.type == "confirmation"
    assert again.reply.text == (
        "The numbers changed since you confirmed, so please check again. "
        "Send a fee reminder to 6 guardians."
    )


async def test_a_failed_precondition_is_refused_in_the_backends_words() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    chat.backend.preflights = [
        rejected("PRECONDITION_FAILED", hint="Nobody in this section has overdue fees right now")
    ]

    result = await chat.say(message="class 6 blue ko reminder bhejo")

    assert result.reply.type == "refusal"
    assert result.reply.text == "Nobody in this section has overdue fees right now"
    assert result.reply.code == "PRECONDITION_FAILED"


async def test_a_name_not_found_is_asked_again_up_to_three_times() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    not_found = rejected("NOT_FOUND", step=1, param="section_id", message="No section matches.")
    chat.backend.preflights = [not_found, not_found, not_found]

    first = await chat.say(message="class 55 ko reminder bhejo")
    assert first.reply.type == "question" and first.reply.code == "NOT_FOUND"
    assert "No section matches" not in first.reply.text  # the backend's developer message stays out
    lookup = next(p.lookup for p in CATALOG["fee.reminder.send"].params if p.name == "section_id")
    assert lookup and first.reply.text == f'I couldn\'t find "class 5". Please type {lookup}.'
    second = await chat.say(message="class 56")
    assert second.reply.type == "question"
    assert chat.backend.preflighted[1].steps[0].params["section_id"] == ParamValue(raw="class 56")

    third = await chat.say(message="class 57")

    assert third.reply.type == "refusal" and third.reply.code == "TOO_MANY_ATTEMPTS"
    assert chat.planner.calls == 1


async def test_the_same_sentence_again_is_planned_from_the_cache_with_a_new_plan_id() -> None:
    chat = Chat(Planner(planned(OVERDUE)))
    chat.backend.preflights = [preflight_ok(confirmation=None, needs_confirming=False)] * 2
    chat.backend.executions = [executed("Overdue fees: 8 students.")] * 2

    first = await chat.say(message="Class 5 Blue ka baqaya dikhao")
    second = await chat.say(message="class 5 blue ka  baqaya dikhao!")

    assert chat.planner.calls == 1
    assert second.reply.text == "Overdue fees: 8 students."
    assert first.reply.type == "answer"
    first_plan, second_plan = (plan for plan, _, _ in chat.backend.executed)
    assert first_plan.plan_id != second_plan.plan_id


async def test_a_model_failure_is_a_refusal_and_is_not_cached() -> None:
    chat = Chat(Planner(ModelOutputError("broken"), Refusal(RefusalReason.NO_MATCHING_CAPABILITY)))

    failed = await chat.say(message="kal ka mausam")
    refused = await chat.say(message="kal ka mausam")

    assert failed.reply.code == "MODEL_FAILED"
    assert refused.reply.code == "no_matching_capability"
    assert chat.planner.calls == 2


async def test_a_new_sentence_while_confirming_replaces_the_waiting_plan() -> None:
    chat = Chat(Planner(planned(REMINDER), planned(OVERDUE)))
    chat.backend.preflights = [
        preflight_ok(confirmation="Send a fee reminder to 6 guardians."),
        preflight_ok(confirmation=None, needs_confirming=False),
    ]
    chat.backend.executions = [executed("Overdue fees: 8 students.")]
    await chat.say(message="class 5 ko sms reminder bhejo")

    result = await chat.say(message="nahi, pehle baqaya dikhao")

    assert result.reply.text == "Overdue fees: 8 students."
    assert [plan.steps[0].capability_id for plan, _, _ in chat.backend.executed] == [
        "fee.overdue.list"
    ]


async def test_a_session_belongs_to_the_user_who_started_it() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    chat.backend.preflights = [preflight_ok(confirmation="Send a fee reminder to 6 guardians.")]
    started = await chat.say(message="class 5 ko sms reminder bhejo")

    chat.backend.user_id = "8"
    other = await chat.say(confirm=True)

    assert other.session_id != started.session_id
    assert (
        other.reply.code == "SESSION_EXPIRED"
    )  # the same as an expired session: nothing is revealed
    assert chat.backend.executed == []


async def test_an_answer_after_the_session_expired_says_so_and_runs_nothing() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    options = [
        EntityCandidate(id="2", label="Class 5 Blue", context="12 students"),
        EntityCandidate(id="3", label="Class 5 Green", context="10 students"),
    ]
    chat.backend.preflights = [
        rejected("AMBIGUOUS_ENTITY", step=1, param="section_id", candidates=options)
    ]
    await chat.say(message="class 5 ko sms reminder bhejo")
    chat.sessions._sessions.clear()  # what the TTL does after 30 minutes

    late = await chat.say(choice="2")
    nothing = await chat.say(choice="2")

    assert late.reply.code == "SESSION_EXPIRED"
    assert nothing.reply.code == "NOTHING_PENDING"  # the new session is known, just idle
    assert len(chat.backend.preflighted) == 1 and chat.backend.executed == []


async def test_a_stale_version_forgets_the_cached_plan() -> None:
    chat = Chat(Planner(planned(OVERDUE), planned(OVERDUE)))
    chat.backend.preflights = [
        rejected("STALE_VERSION", status=409),
        preflight_ok(confirmation=None, needs_confirming=False),
    ]
    chat.backend.executions = [executed("Overdue fees: 8 students.")]

    stale = await chat.say(message="class 5 blue ka baqaya dikhao")
    fresh = await chat.say(message="class 5 blue ka baqaya dikhao")

    assert stale.reply.code == "STALE_VERSION"
    assert fresh.reply.type == "answer"
    assert chat.planner.calls == 2


async def test_a_traced_turn_shows_each_stage_without_any_token() -> None:
    chat = Chat(Planner(planned(REMINDER)))
    options = [
        EntityCandidate(id="2", label="Class 5 Blue", context="12 students"),
        EntityCandidate(id="3", label="Class 5 Green", context="10 students"),
    ]
    chat.backend.preflights = [
        rejected("AMBIGUOUS_ENTITY", step=1, param="section_id", candidates=options),
        preflight_ok(confirmation="Send a fee reminder to 6 guardians in Class 5 Blue by SMS."),
    ]
    chat.backend.executions = [executed("Reminders sent to 6 guardians.")]

    with trace.collect() as asked:
        await chat.say(message="class 5 ko sms reminder bhejo")
    with trace.collect() as chosen:
        await chat.say(choice="2")
    with trace.collect() as confirmed:
        await chat.say(confirm=True)

    def keys(collected: trace.Trace) -> list[str]:
        return [stage.key for stage in collected.stages]

    assert keys(asked) == ["session", "state", "plan_cache", "preflight", "reply"]
    assert keys(chosen) == ["session", "state", "read_answer", "preflight", "reply"]
    assert keys(confirmed) == ["session", "state", "execute", "reply"]

    preflight = asked.stages[3]
    assert (
        preflight.status == "paused" and preflight.output["refused"]["code"] == "AMBIGUOUS_ENTITY"
    )
    assert chosen.stages[2].output == {"parameter_now": {"raw": "class 5", "chosen_id": "2"}}
    assert asked.stages[1].input["phase_before"] == "idle"
    assert asked.stages[1].output == {"phase_after": "awaiting_input"}
    assert confirmed.stages[-1].output["text"] == "Reminders sent to 6 guardians."

    shown = str([t.as_json() for t in (asked, chosen, confirmed)])
    assert "user-token" not in shown
    assert "token-Send" not in shown  # the preflight token
