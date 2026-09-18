package com.diversive.agent.execute;

import static com.diversive.agent.fixtures.PreflightFixtures.EDITOR;
import static com.diversive.agent.preflight.PreflightTestSupport.chosen;
import static com.diversive.agent.preflight.PreflightTestSupport.fromStep;
import static com.diversive.agent.preflight.PreflightTestSupport.plan;
import static com.diversive.agent.preflight.PreflightTestSupport.raw;
import static com.diversive.agent.preflight.PreflightTestSupport.step;
import static com.diversive.agent.preflight.PreflightTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.diversive.agent.error.AgentRejectionException;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandlerWithEditedDescription;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.fixtures.NotesCapabilities.ShareFolderRequest;
import com.diversive.agent.fixtures.NotesCapabilities.ShareResult;
import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.preflight.PreflightTestSupport;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.diversive.agent.spi.AuditEvent;
import com.diversive.agent.token.PreflightTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Execute re-checks everything, runs each step once, verifies it, records it, and says what ran. */
class ExecuteServiceTest {

    private final ExecuteTestSupport world = new ExecuteTestSupport();

    private static PlanStep share(int number, Object folder) {
        return step(number, "notes.folder.share", "folder_id", folder, "channel", value("sms"));
    }

    private static PlanStep archive(int number, String note) {
        return step(number, "notes.note.archive", "note_id", raw(note), "reason", value("Trip is over"));
    }

    // --- A step that runs ----------------------------------------------------------------------------------

    @Test
    void aConfirmedWriteRunsOnceAndIsRecordedAsStartedThenSucceeded() {
        Plan plan = plan(share(1, raw("recipes")));

        ExecuteResponse response = world.run(plan, world.confirm(plan));

        assertThat(response.outcome()).isEqualTo(ExecuteOutcome.COMPLETED);
        ExecutedStep step = response.steps().getFirst();
        assertThat(step.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step.reply()).isEqualTo("Shared 4 notes from Recipes, 2048 bytes in all.");
        assertThat(step.count()).isEqualTo(4);
        assertThat(step.data()).isEqualTo(new ShareResult(4, 2048));
        assertThat(world.store.shares).containsExactly("folder 7 by sms: 4 notes");

        assertThat(world.audit.kinds()).containsExactly(AuditEvent.Kind.STARTED, AuditEvent.Kind.SUCCEEDED);
        AuditEvent succeeded = world.audit.events().getLast();
        assertThat(succeeded.idempotencyKey()).isEqualTo("session-1:plan-1:1");
        assertThat(succeeded.sentence()).isEqualTo(ExecuteTestSupport.SENTENCE);
        assertThat(succeeded.userId()).isEqualTo("user-7");
        assertThat(succeeded.params()).containsExactlyInAnyOrderEntriesOf(Map.of("folder_id", "7", "channel", "sms"));
        assertThat(succeeded.labels()).containsExactlyInAnyOrderEntriesOf(Map.of("folder_name", "Recipes"));
        assertThat(succeeded.confirmedCount()).isEqualTo(4);
        assertThat(succeeded.count()).isEqualTo(4);
        assertThat(succeeded.result()).contains("\"reply\":\"Shared 4 notes from Recipes, 2048 bytes in all.\"");
    }

    @Test
    void replayingTheSameSessionPlanAndStepDoesNotRunTheWriteTwice() {
        Plan plan = plan(share(1, raw("recipes")));
        String token = world.confirm(plan);

        ExecuteResponse first = world.run(plan, token);
        ExecuteResponse second = world.run(plan, token);

        assertThat(second.steps().getFirst().status()).isEqualTo(StepStatus.REPLAYED);
        assertThat(second.steps().getFirst().reply()).isEqualTo(first.steps().getFirst().reply());
        assertThat(second.outcome()).isEqualTo(ExecuteOutcome.COMPLETED);
        assertThat(world.store.shares).hasSize(1);
        assertThat(world.audit.kinds())
                .containsExactly(AuditEvent.Kind.STARTED, AuditEvent.Kind.SUCCEEDED, AuditEvent.Kind.REPLAYED);
    }

    @Test
    void aReadRunsAgainEveryTimeBecauseResultsAreNeverReplayed() {
        Plan plan = plan(step(1, "notes.note.find", "text", value("list"), "pinned_only", value(false)));
        String token = world.confirm(plan);

        ExecuteResponse first = world.run(plan, token);
        world.store.notes.put(14L, "Guest list");
        ExecuteResponse second = world.run(plan, token);

        assertThat(first.steps().getFirst().reply()).isEqualTo("Found 3 notes.");
        assertThat(second.steps().getFirst().reply()).isEqualTo("Found 4 notes.");
        assertThat(second.steps().getFirst().status()).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void aValueFromAnEarlierStepIsTakenFromWhatThatStepReturned() {
        Plan plan = plan(step(1, "notes.note.copy", "note_id", raw("packing")),
                step(2, "notes.note.pin", "note_id", fromStep(1, "copy_id")));

        ExecuteResponse response = world.run(plan, world.confirm(plan));

        assertThat(response.steps()).extracting(ExecutedStep::reply)
                .containsExactly("Copied Packing list as note 100.", "Pinned note 100.");
        assertThat(world.store.pinned).containsExactly(100L);
    }

    // --- What changed between preflight and execute ------------------------------------------------------------

    @Test
    void stateThatChangedSinceTheConfirmationFailsItsPrecondition() {
        Plan plan = plan(archive(1, "packing"));
        String token = world.confirm(plan);
        world.store.archived.add(12L);

        ExecuteResponse response = world.run(plan, token);

        ExecutedStep step = response.steps().getFirst();
        assertThat(response.outcome()).isEqualTo(ExecuteOutcome.FAILED);
        assertThat(step.status()).isEqualTo(StepStatus.FAILED);
        assertThat(step.error().code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(step.error().hint()).isEqualTo("That note is already archived");
        assertThat(world.audit.kinds()).containsExactly(AuditEvent.Kind.REFUSED);
        assertThat(world.audit.events().getFirst().errorCode()).isEqualTo("PRECONDITION_FAILED");
    }

    @Test
    void anyChangeToASmallCountStopsTheWrite() {
        Plan plan = plan(share(1, raw("recipes")));
        String token = world.confirm(plan);
        world.store.notesInFolder.put(7L, 5L);

        ExecutedStep step = world.run(plan, token).steps().getFirst();

        assertThat(step.error().code()).isEqualTo("COUNT_CHANGED");
        assertThat(step.error().confirmedCount()).isEqualTo(4);
        assertThat(step.error().currentCount()).isEqualTo(5);
        assertThat(step.error().message()).contains("the confirmation said 4 notes, but it would now be 5");
        assertThat(world.store.shares).isEmpty();
    }

    @Test
    void aLargeCountMayMoveAFewPercentButNoMore() {
        world.store.notesInFolder.put(8L, 1000L);
        Plan plan = plan(share(1, chosen("travel", "8")));
        String token = world.confirm(plan);

        world.store.notesInFolder.put(8L, 1030L);
        assertThat(world.run(plan, token).steps().getFirst().count()).isEqualTo(1030);

        Plan second = new Plan("plan-2", "session-1", plan.steps());
        String secondToken = world.confirm(second);
        world.store.notesInFolder.put(8L, 1200L);
        assertThat(world.run(second, secondToken).steps().getFirst().error().code()).isEqualTo("COUNT_CHANGED");
    }

    @Test
    void aRecordTheUserCanNoLongerSeeIsOutOfScope() {
        Plan plan = plan(share(1, raw("recipes")));
        String token = world.confirm(plan);
        world.store.folders.remove(7L);

        ExecutedStep step = world.run(plan, token).steps().getFirst();

        assertThat(step.error().code()).isEqualTo("OUT_OF_SCOPE");
        assertThat(step.error().message()).isEqualTo("Step 1: the folder that was confirmed is not available");
        assertThat(world.store.shares).isEmpty();
    }

    // --- A handler that misbehaves -------------------------------------------------------------------------------

    @Test
    void aHandlerThatTouchesADifferentNumberThanWasCountedFailsVerification() {
        world.handlers.put(ShareAndFindHandlers.class, new ShareAndFindHandlers(world.store) {
            @Override
            public ShareResult share(ShareFolderRequest request) {
                return new ShareResult(99, 1);
            }
        });
        Plan plan = plan(share(1, raw("recipes")));

        ExecutedStep step = world.run(plan, world.confirm(plan)).steps().getFirst();

        assertThat(step.error().code()).isEqualTo("VERIFICATION_FAILED");
        assertThat(world.audit.kinds()).containsExactly(AuditEvent.Kind.STARTED, AuditEvent.Kind.FAILED);
    }

    @Test
    void aHandlerThatBreaksIsExecutionFailedWithoutItsMessage() {
        world.handlers.put(ShareAndFindHandlers.class, new ShareAndFindHandlers(world.store) {
            @Override
            public ShareResult share(ShareFolderRequest request) {
                throw new IllegalStateException("database password is hunter2");
            }
        });
        Plan plan = plan(share(1, raw("recipes")));

        ExecutedStep step = world.run(plan, world.confirm(plan)).steps().getFirst();

        assertThat(step.error().code()).isEqualTo("EXECUTION_FAILED");
        assertThat(step.error().message()).doesNotContain("hunter2");
        assertThat(world.audit.events().getLast().errorCode()).isEqualTo("EXECUTION_FAILED");
    }

    // --- A plan of several steps --------------------------------------------------------------------------------

    @Test
    void aFailedStepStopsThePlanAndWhatAlreadyRanStaysDone() {
        Plan plan = plan(archive(1, "packing"), share(2, raw("recipes")), step(3, "notes.library.sweep"));
        String token = world.confirm(plan);
        world.store.notesInFolder.put(7L, 0L);

        ExecuteResponse response = world.run(plan, token);

        assertThat(response.outcome()).isEqualTo(ExecuteOutcome.PARTIAL);
        assertThat(response.steps()).extracting(ExecutedStep::status)
                .containsExactly(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.NOT_RUN);
        assertThat(response.steps().get(1).error().code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(world.store.archived).contains(12L);
    }

    // --- Refused before any step runs ---------------------------------------------------------------------------

    @Test
    void aTokenForAnotherPlanRefusesTheWholePlanAndIsRecorded() {
        Plan confirmed = plan(share(1, raw("recipes")));
        Plan edited = plan(share(1, raw("travel plans")));
        String token = world.confirm(confirmed);

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class, () -> world.run(edited, token));

        assertThat(rejection.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejection.error().code()).isEqualTo("TOKEN_INVALID");
        assertThat(world.store.shares).isEmpty();
        assertThat(world.audit.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(AuditEvent.Kind.REFUSED);
                    assertThat(event.errorCode()).isEqualTo("TOKEN_INVALID");
                });
    }

    @Test
    void aTokenPastItsFiveMinutesIsExpired() {
        Plan plan = plan(share(1, raw("recipes")));
        String token = world.confirm(plan);
        world.execute = world.execute(PreflightTestSupport.REGISTRY, new PreflightTokens(PreflightTestSupport.SECRET,
                Duration.ofMinutes(5), Clock.offset(PreflightTestSupport.CLOCK, Duration.ofMinutes(6))));

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class, () -> world.run(plan, token));

        assertThat(rejection.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejection.error().code()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void aCapabilityWhoseMetadataChangedSinceTheConfirmationIsStale() {
        Plan plan = plan(archive(1, "packing"));
        String token = world.confirm(plan);
        CapabilityRegistry edited = new CapabilityRegistryBuilder(PreflightTestSupport.MAPPER).build(
                List.of(PreflightFixtures.handlers().getFirst(), ArchiveHandlerWithEditedDescription.class,
                        PreflightFixtures.CopyAndPinHandlers.class, PreflightFixtures.SweepHandler.class,
                        PreflightFixtures.RestoreHandler.class),
                PreflightFixtures.checkIds(), PreflightFixtures.countIds(), PreflightFixtures.resolverTypes());
        world.execute = world.execute(edited, PreflightTestSupport.tokens());

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class, () -> world.run(plan, token));

        assertThat(rejection.error().code()).isEqualTo("STALE_VERSION");
    }

    @Test
    void theSentenceIsRequiredForTheAuditTrail() {
        Plan plan = plan(share(1, raw("recipes")));
        String token = world.confirm(plan);

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> world.execute.execute(new ExecuteRequest(plan, token, " "), EDITOR));

        assertThat(rejection.error().code()).isEqualTo("INVALID_PLAN");
        assertThat(world.store.shares).isEmpty();
    }
}
