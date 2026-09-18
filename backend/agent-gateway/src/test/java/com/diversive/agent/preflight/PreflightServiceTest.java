package com.diversive.agent.preflight;

import static com.diversive.agent.fixtures.PreflightFixtures.EDITOR;
import static com.diversive.agent.fixtures.PreflightFixtures.READER;
import static com.diversive.agent.preflight.PreflightTestSupport.chosen;
import static com.diversive.agent.preflight.PreflightTestSupport.fromStep;
import static com.diversive.agent.preflight.PreflightTestSupport.plan;
import static com.diversive.agent.preflight.PreflightTestSupport.raw;
import static com.diversive.agent.preflight.PreflightTestSupport.service;
import static com.diversive.agent.preflight.PreflightTestSupport.step;
import static com.diversive.agent.preflight.PreflightTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.error.AgentRejectionException;
import com.diversive.agent.error.EntityCandidate;
import com.diversive.agent.fixtures.NotesCapabilities.Channel;
import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanHasher;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.token.PreflightToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The four passes, every way they refuse, and the confirmation and token they produce. */
class PreflightServiceTest {

    private final PreflightService preflight = service();

    private static PlanStep share(String folder) {
        return step(1, "notes.folder.share", "folder_id", raw(folder), "channel", value("sms"));
    }

    private AgentErrorResponse refusal(Plan plan, HttpStatus status) {
        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> preflight.preflight(plan, EDITOR));
        assertThat(rejection).as("preflight should have refused the plan").isNotNull();
        assertThat(rejection.status()).isEqualTo(status);
        return rejection.error();
    }

    // --- Resolve ----------------------------------------------------------------------------------

    @Test
    void anUnambiguousNameResolvesToAnIdAndTheLabelTheConfirmationUses() {
        PreflightResponse response = preflight.preflight(plan(share("recipes")), EDITOR);

        assertThat(response.steps().getFirst().resolved()).containsExactly(new ResolvedEntity("folder_id", "7", "Recipes"));
        assertThat(response.confirmation()).isEqualTo("Share 4 notes in Recipes by sms. This cannot be undone.");
    }

    @Test
    void anAmbiguousNameReturnsAmbiguousEntityWithItsCandidates() {
        AgentErrorResponse error = refusal(plan(share("travel")), HttpStatus.UNPROCESSABLE_ENTITY, alwaysShareable());

        assertThat(error.code()).isEqualTo("AMBIGUOUS_ENTITY");
        assertThat(error.step()).isEqualTo(1);
        assertThat(error.param()).isEqualTo("folder_id");
        assertThat(error.candidates()).containsExactly(
                new EntityCandidate("8", "Travel plans", "folder"),
                new EntityCandidate("9", "Travel receipts", "folder"));
    }

    @Test
    void onlyMatchesTheStepCouldActOnAreOffered() {
        // "list" matches three notes; "Old shopping list" is archived already, so archiving it is not offered.
        Plan archive = plan(step(1, "notes.note.archive", "note_id", raw("list"), "reason", value("done")));

        AgentErrorResponse error = refusal(archive, HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(error.code()).isEqualTo("AMBIGUOUS_ENTITY");
        assertThat(error.candidates()).extracting(EntityCandidate::label).containsExactly("Shopping list", "Packing list");
    }

    @Test
    void theOneMatchTheStepCouldActOnIsTheRecordWithNothingToAsk() {
        // "Travel receipts" is empty, so sharing "travel" can only mean "Travel plans".
        PreflightResponse response = preflight.preflight(plan(share("travel")), EDITOR);

        assertThat(response.steps().getFirst().resolved()).containsExactly(new ResolvedEntity("folder_id", "8", "Travel plans"));
        assertThat(response.confirmation()).isEqualTo("Share 2 notes in Travel plans by sms. This cannot be undone.");
    }

    @Test
    void matchesThatBreakTheSameFirstRuleAreAllOfferedSoChoosingOneExplainsWhy() {
        PreflightService nothingShareable = service(List.of(
                PreconditionCheck.of("folder_not_empty", (params, user) -> false),
                PreconditionCheck.of("note_not_archived", (params, user) -> true)), PreflightFixtures.counts(),
                PreflightFixtures.resolvers());

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> nothingShareable.preflight(plan(share("travel")), EDITOR));

        assertThat(rejection.error().code()).isEqualTo("AMBIGUOUS_ENTITY");
        assertThat(rejection.error().candidates()).extracting(EntityCandidate::id).containsExactly("8", "9");
    }

    private static PreflightService alwaysShareable() {
        return service(List.of(
                PreconditionCheck.of("folder_not_empty", (params, user) -> true),
                PreconditionCheck.of("note_not_archived", (params, user) -> true)), PreflightFixtures.counts(),
                PreflightFixtures.resolvers());
    }

    private AgentErrorResponse refusal(Plan plan, HttpStatus status, PreflightService service) {
        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> service.preflight(plan, EDITOR));
        assertThat(rejection).as("preflight should have refused the plan").isNotNull();
        assertThat(rejection.status()).isEqualTo(status);
        return rejection.error();
    }

    @Test
    void theUsersChoiceIsSentBackWithTheSameWords() {
        Plan answered = plan(step(1, "notes.folder.share", "folder_id", chosen("travel", "8"), "channel", value("sms")));

        PreflightResponse response = preflight.preflight(answered, EDITOR);

        assertThat(response.confirmation()).isEqualTo("Share 2 notes in Travel plans by sms. This cannot be undone.");
    }

    @Test
    void aChoiceTheWordsDoNotMatchIsNotFoundSoNoIdCanBeSlippedIn() {
        Plan slipped = plan(step(1, "notes.folder.share", "folder_id", chosen("travel", "7"), "channel", value("sms")));

        AgentErrorResponse error = refusal(slipped, HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.message()).contains("the chosen folder is not one that \"travel\" matches");
    }

    @Test
    void aNameMatchingNothingIsNotFound() {
        AgentErrorResponse error = refusal(plan(share("gardening")), HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.param()).isEqualTo("folder_id");
        assertThat(error.message()).isEqualTo("Step 1: no folder matches \"gardening\"");
    }

    @Test
    void aNameMatchingNothingIsReportedBeforeAnEarlierAmbiguity() {
        Plan plan = plan(share("travel"),
                step(2, "notes.note.archive", "note_id", raw("diary"), "reason", value("done")));

        AgentErrorResponse error = refusal(plan, HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.step()).isEqualTo(2);
    }

    @Test
    void candidatesAreCappedSoAVagueNameAsksForMoreWords() {
        List<EntityMatch> many = IntStream.rangeClosed(1, 12).mapToObj(i -> new EntityMatch("" + i, "Folder " + i, null)).toList();
        List<EntityResolver> resolvers = new ArrayList<>(PreflightFixtures.resolvers());
        resolvers.set(0, EntityResolver.of("folder", (raw, user) -> many));
        PreflightService vague = service(List.of(
                PreconditionCheck.of("folder_not_empty", (params, user) -> true),
                PreconditionCheck.of("note_not_archived", (params, user) -> true)), PreflightFixtures.counts(), resolvers);

        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> vague.preflight(plan(share("folder")), EDITOR));

        assertThat(rejection.error().candidates()).hasSize(10);
        assertThat(rejection.error().message()).endsWith("matches 12 folder records; the first 10 are listed");
    }

    @Test
    void aRuleAcrossFieldsIsCheckedOnTheWholeRequestBeforeAnyPrecondition() {
        Plan plan = plan(step(1, "notes.folder.share", "folder_id", raw("receipts"), "channel", value("sms"),
                "cover_note", value("Please read")));

        AgentErrorResponse error = refusal(plan, HttpStatus.BAD_REQUEST);

        assertThat(error.code()).isEqualTo("INVALID_PLAN");
        assertThat(error.message()).isEqualTo("Step 1: a cover note can only go by email");
    }

    // --- Check ------------------------------------------------------------------------------------

    @Test
    void aFailingPreconditionReturnsPreconditionFailedWithItsHint() {
        AgentErrorResponse error = refusal(plan(share("receipts")), HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(error.code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(error.precondition()).isEqualTo("folder_not_empty");
        assertThat(error.hint()).isEqualTo("There is nothing in this folder to share");
        assertThat(error.message()).isEqualTo(error.hint());
    }

    @Test
    void checksAndCountsReceiveResolvedIdsAndValuesOfTheRecordsTypes() {
        Map<String, Object> seen = new HashMap<>();
        List<PreconditionCheck> checks = List.of(
                PreconditionCheck.of("folder_not_empty", (params, user) -> {
                    seen.putAll(params);
                    return true;
                }),
                PreconditionCheck.of("note_not_archived", (params, user) -> true));

        service(checks, PreflightFixtures.counts(), PreflightFixtures.resolvers()).preflight(plan(share("recipes")), EDITOR);

        assertThat(seen).containsExactlyInAnyOrderEntriesOf(Map.of("folder_id", 7L, "channel", Channel.SMS));
    }

    // --- Count and compose --------------------------------------------------------------------------

    @Test
    void theConfirmationIsFullyRenderedWithTheRealCount() {
        PreflightResponse response = preflight.preflight(plan(share("recipes")), EDITOR);

        PreflightStepResult step = response.steps().getFirst();
        assertThat(step.count()).isEqualTo(4);
        assertThat(step.unit()).isEqualTo("notes");
        assertThat(step.line()).isEqualTo("Share 4 notes in Recipes by sms.");
        assertThat(response.requiresConfirmation()).isTrue();
        assertThat(response.warnings()).containsExactly("This cannot be undone.");
    }

    @Test
    void aParameterThePlanLeavesOutTakesItsDeclaredDefault() {
        Plan withoutChannel = plan(step(1, "notes.folder.share", "folder_id", raw("recipes")));

        assertThat(preflight.preflight(withoutChannel, EDITOR).confirmation())
                .isEqualTo("Share 4 notes in Recipes by email. This cannot be undone.");
    }

    @Test
    void aSingleRecordWriteCountsOneAndAReversibleOneHasNoWarning() {
        Plan archive = plan(step(1, "notes.note.archive", "note_id", raw("packing"), "reason", value("Trip is over")));

        PreflightResponse response = preflight.preflight(archive, EDITOR);

        assertThat(response.steps().getFirst().count()).isEqualTo(1);
        assertThat(response.steps().getFirst().unit()).isNull();
        assertThat(response.confirmation()).isEqualTo("Archive Packing list.");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void severalWritesAreNumberedAndWarnedAboutByPosition() {
        Plan plan = plan(share("recipes"),
                step(2, "notes.note.archive", "note_id", raw("packing"), "reason", value("Trip is over")),
                step(3, "notes.library.sweep"));

        PreflightResponse response = preflight.preflight(plan, EDITOR);

        assertThat(response.confirmation()).isEqualTo("""
                1. Share 4 notes in Recipes by sms.
                2. Archive Packing list.
                3. Sweep 12 old notes out of the library.

                Steps 1 and 3 cannot be undone. Step 3 affects everything of its kind in the branch.""");
        assertThat(response.warnings()).containsExactly(
                "Steps 1 and 3 cannot be undone.", "Step 3 affects everything of its kind in the branch.");
    }

    @Test
    void aStepThatNeedsAnEarlierStepsResultIsConfirmedWithItsPendingTemplate() {
        Plan plan = plan(step(1, "notes.note.copy", "note_id", raw("packing")),
                step(2, "notes.note.pin", "note_id", fromStep(1, "copy_id")));

        PreflightResponse response = preflight.preflight(plan, EDITOR);

        assertThat(response.confirmation()).isEqualTo("1. Copy Packing list.\n2. Pin the new copy.\n\nStep 1 cannot be undone.");
        assertThat(response.steps().get(1).pending()).isTrue();
        assertThat(response.steps().get(1).count()).isNull();
    }

    @Test
    void aPlanOfReadsNeedsNoConfirmationButIsStillReadAndSigned() {
        Plan find = plan(step(1, "notes.note.find", "text", value("milk"), "tag_ids", value(List.of(1, 2)),
                "since", value("2026-09-01"), "minimum_size", value("2.5"), "pinned_only", value(true)));

        PreflightResponse response = preflight.preflight(find, EDITOR);

        assertThat(response.requiresConfirmation()).isFalse();
        assertThat(response.confirmation()).isNull();
        assertThat(response.warnings()).isEmpty();
        assertThat(response.steps().getFirst().line()).isNull();
        assertThat(response.steps().getFirst().count()).isNull();
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void aCountPublishingAFactItDidNotDeclareIsABugNotAConfirmation() {
        List<AffectedCount> counts = List.of(
                AffectedCount.of("notes.folder.share", (params, user) -> new CountResult(4, "notes", Map.of("surprise", 1))),
                AffectedCount.of("notes.library.sweep", (params, user) -> new CountResult(1, "notes", Map.of())));

        assertThatThrownBy(() -> service(PreflightFixtures.checks(), counts, PreflightFixtures.resolvers())
                .preflight(plan(share("recipes")), EDITOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[surprise]");
    }

    // --- Token ------------------------------------------------------------------------------------

    @Test
    void theTokenVouchesForThisPlanItsResolvedIdsAndCounts() throws Exception {
        Plan plan = plan(share("recipes"));

        PreflightResponse response = preflight.preflight(plan, EDITOR);
        PreflightToken payload = PreflightTestSupport.tokens().verify(response.token(), plan, EDITOR);

        assertThat(payload.planHash()).isEqualTo(PlanHasher.hash(plan));
        assertThat(payload.userId()).isEqualTo("user-7");
        assertThat(payload.steps()).containsExactly(new PreflightToken.Step(1, Map.of("folder_id", "7"), 4L, false));
        assertThat(response.expiresAt()).isEqualTo("2026-09-14T09:05:00Z");
    }

    // --- Refused before any data is read -------------------------------------------------------------

    @Test
    void aStepPlannedWithAnOlderVersionIsStaleEvenIfItsParametersNoLongerFit() {
        Plan old = plan(new PlanStep(1, "notes.folder.share", "0".repeat(64), Map.of("colour", value("red"))));

        AgentErrorResponse error = refusal(old, HttpStatus.CONFLICT);

        assertThat(error.code()).isEqualTo("STALE_VERSION");
        assertThat(error.step()).isEqualTo(1);
    }

    @Test
    void aCapabilityTheUserMayNotUseIsNotPermitted() {
        AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                () -> preflight.preflight(plan(share("recipes")), READER));

        assertThat(rejection.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejection.error().code()).isEqualTo("NOT_PERMITTED");
    }

    @Test
    void aNotImplementedCapabilityIsRefusedBeforeAnythingIsLookedUpOrChecked() {
        // Its resolver and precondition have no beans at all: reaching either would throw, not refuse.
        Plan restore = plan(step(1, "notes.note.restore", "note_id", raw("shopping")));

        AgentErrorResponse error = refusal(restore, HttpStatus.NOT_IMPLEMENTED);

        assertThat(error.code()).isEqualTo("NOT_IMPLEMENTED");
        assertThat(error.message()).isEqualTo("Step 1: notes.note.restore is not available yet");
    }

    @Test
    void aPlanThatDoesNotFitTheMetadataIsInvalid() {
        record Case(Plan plan, String message) {
        }
        List<Case> cases = List.of(
                new Case(plan(new PlanStep(1, "notes.folder.shred", "v", Map.of())), "notes.folder.shred is not a registered capability"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", raw("recipes"), "colour", value("red"))),
                        "'colour': notes.folder.share takes no such parameter"),
                new Case(plan(step(1, "notes.folder.share")), "'folder_id': it is required, and the plan does not give it"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", value(7))), "'folder_id': it is looked up by name"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", raw("x".repeat(201)))),
                        "'folder_id': the words to look up are longer than 200 characters"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", raw("recipes"), "channel", raw("text me"))),
                        "'channel': it takes a value as given, not raw words to look up"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", raw("recipes"), "channel", value(5))),
                        "'channel': must be text"),
                new Case(plan(step(1, "notes.folder.share", "folder_id", raw("recipes"), "channel", value("pigeon"))),
                        "'channel': must be one of email, sms"),
                new Case(plan(step(1, "notes.note.find", "pinned_only", value(true), "tag_ids", value(List.of(1, 2.5)))),
                        "'tag_ids': must be a list, each item a whole number"),
                new Case(plan(step(1, "notes.note.find", "pinned_only", value(true), "minimum_size", value("lots"))),
                        "'minimum_size': must be a number"),
                new Case(plan(step(1, "notes.note.find", "pinned_only", value(true), "since", value("14/09/2026"))),
                        "'since': must be a date written as YYYY-MM-DD"),
                new Case(plan(step(1, "notes.note.archive", "note_id", raw("packing"), "reason", value(" "))),
                        "'reason': must not be blank"),
                new Case(plan(step(1, "notes.note.archive", "note_id", raw("packing"), "reason", value("x".repeat(41)))),
                        "'reason': size must be between 0 and 40"),
                new Case(plan(step(1, "notes.note.archive", "note_id", new ParamValue("x", "y", null, null, null),
                        "reason", value("done"))), "'note_id': give exactly one of"),
                new Case(plan(step(1, "notes.note.pin", "note_id", fromStep(1, "copy_id"))), "from_step must name an earlier step"),
                new Case(plan(step(1, "notes.note.copy", "note_id", raw("packing")),
                        step(2, "notes.note.pin", "note_id", fromStep(1, "note_title"))), "publishes no fact 'note_title'"),
                new Case(plan(share("a"), share("b"), share("c"), share("d")), "The plan has 4 steps; at most 3 are allowed"),
                new Case(plan(share("recipes"), share("recipes")), "the step at position 2 is numbered 1"),
                new Case(new Plan("plan-1", " ", List.of(share("recipes"))), "The plan needs a plan_id and a session_id"));

        for (Case example : cases) {
            AgentRejectionException rejection = catchThrowableOfType(AgentRejectionException.class,
                    () -> preflight.preflight(example.plan(), EDITOR));
            assertThat(rejection).as(example.message()).isNotNull();
            assertThat(rejection.status()).as(example.message()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejection.error().code()).as(example.message()).isEqualTo("INVALID_PLAN");
            assertThat(rejection.error().message()).as(example.message()).contains(example.message());
        }
    }
}
