package com.diversive.agent.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diversive.agent.fixtures.BrokenCapabilities.BadlyShaped;
import com.diversive.agent.fixtures.BrokenCapabilities.ConfirmationWithGaps;
import com.diversive.agent.fixtures.BrokenCapabilities.ListOfNamesToLookUp;
import com.diversive.agent.fixtures.BrokenCapabilities.NotImplementedWithUnknownPlaceholder;
import com.diversive.agent.fixtures.BrokenCapabilities.ReturnsTooLittle;
import com.diversive.agent.fixtures.BrokenCapabilities.TemplateWithUnknownPlaceholder;
import com.diversive.agent.fixtures.NotesCapabilities;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandlerForgettingItsSibling;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.fixtures.PreflightFixtures.RestoreHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every registry rule rejects what it exists to reject, and reports every problem at once. */
class RegistryRulesTest {

    private final CapabilityRegistryBuilder builder = new CapabilityRegistryBuilder(JsonMapper.builder().build());

    private List<String> problems(Collection<Class<?>> handlers, List<String> checkIds, List<String> countIds,
                                  List<String> resolverTypes) {
        try {
            builder.build(handlers, checkIds, countIds, resolverTypes);
        } catch (CapabilityRegistryException exception) {
            return exception.problems();
        }
        return List.of();
    }

    private List<String> problems(Collection<Class<?>> handlers, List<String> checkIds, List<String> countIds) {
        return problems(handlers, checkIds, countIds, NotesCapabilities.RESOLVER_TYPES);
    }

    private static final List<Class<?>> VALID = List.of(ShareAndFindHandlers.class, ArchiveHandler.class);

    @Test
    void theValidFixturePassesEveryRule() {
        assertThat(problems(VALID, NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS)).isEmpty();
    }

    @Test
    void assertion1_aPreconditionWithoutACheckBeanIsRejected() {
        assertThat(problems(VALID, List.of("note_not_archived"), NotesCapabilities.COUNT_IDS)).containsExactly(
                "notes.folder.share: precondition 'folder_not_empty' has no PreconditionCheck bean");
    }

    @Test
    void assertion2_oneDirectionalDisambiguateFromIsRejected() {
        List<Class<?>> oneSided = List.of(ShareAndFindHandlers.class, ArchiveHandlerForgettingItsSibling.class);

        assertThat(problems(oneSided, NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS)).containsExactly(
                "notes.folder.share: disambiguateFrom names 'notes.note.archive', but 'notes.note.archive' "
                        + "does not name 'notes.folder.share' back (disambiguateFrom must be symmetric)");
    }

    @Test
    void assertion2_aSiblingThatIsNotRegisteredIsRejected() {
        assertThat(problems(List.of(ShareAndFindHandlers.class), List.of("folder_not_empty"), NotesCapabilities.COUNT_IDS,
                List.of("folder")))
                .containsExactly("notes.folder.share: disambiguateFrom names 'notes.note.archive', "
                        + "which is not a registered capability");
    }

    @Test
    void assertion3_aPlaceholderNothingProducesIsRejected() {
        assertThat(problems(List.of(TemplateWithUnknownPlaceholder.class), List.of(), List.of(), List.of())).containsExactly(
                "notes.note.pin: confirmation template uses {mystery}, which is not a parameter, "
                        + "a resolved label, count, or a declared fact");
    }

    @Test
    void assertion4_aGroupCapabilityWithoutACountIsRejected() {
        assertThat(problems(VALID, NotesCapabilities.CHECK_IDS, List.of())).containsExactly(
                "notes.folder.share: blast radius group is above single, but there is no AffectedCount bean for it");
    }

    @Test
    void aParameterWhoseResolverHasNoBeanIsRejected() {
        assertThat(problems(VALID, NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS, List.of("folder")))
                .containsExactly("notes.note.archive: param 'note_id' resolves 'note', but there is no EntityResolver bean for it");
    }

    @Test
    void aResolverBeanNoParameterNamesIsRejected() {
        assertThat(problems(VALID, NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS,
                List.of("folder", "note", "tag", "note")))
                .containsExactlyInAnyOrder(
                        "two EntityResolver beans claim the id 'note'",
                        "EntityResolver bean for 'tag' is named by no parameter");
    }

    @Test
    void aNotImplementedCapabilityNeedsNoChecksCountsOrResolvers() {
        List<Class<?>> withRestore = List.of(ShareAndFindHandlers.class, ArchiveHandler.class, RestoreHandler.class);

        assertThat(problems(withRestore, NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS)).isEmpty();
    }

    @Test
    void aNotImplementedCapabilityStillObeysTheRulesAboutItsMetadata() {
        List<String> problems = problems(List.of(NotImplementedWithUnknownPlaceholder.class), List.of(), List.of(), List.of());

        assertThat(problems).containsExactly("notes.note.shred: confirmation template uses {mystery}, which is not "
                + "a parameter, a resolved label, count, or a declared fact");
    }

    @Test
    void aConfirmationThatCouldHaveAGapIsRejected() {
        assertThat(problems(List.of(ConfirmationWithGaps.class), List.of(), List.of(), List.of("note"))).containsExactlyInAnyOrder(
                "notes.note.label: confirmation template uses {colour}, which is optional, so the confirmation could have a gap",
                "notes.note.label: confirmation template uses {other_title}, the label of optional param 'other_note_id', "
                        + "so the confirmation could have a gap",
                "notes.note.label: confirmation template uses fact {label_count}, which only an AffectedCount bean "
                        + "can supply before it runs, and there is none");
    }

    @Test
    void aListOfNamesToLookUpIsRejected() {
        assertThat(problems(List.of(ListOfNamesToLookUp.class), List.of(), List.of(), List.of("tag")))
                .containsExactly("notes.note.tag (ListOfNamesToLookUp.tag): param 'tag_ids' is a list, and preflight "
                        + "cannot look up a list of names yet; take one name, or no resolver");
    }

    @Test
    void aHandlerMustReturnTheFactsItDeclaresAndTheCountExecuteVerifies() {
        assertThat(problems(List.of(ReturnsTooLittle.class), List.of(), List.of("notes.folder.tidy"), List.of()))
                .containsExactlyInAnyOrder(
                        "notes.note.star: fact 'star_count' is not a property of what its handler returns (StarResult)",
                        "notes.folder.tidy: what its handler returns (void) needs a 'count' property, because execute "
                                + "checks it against the confirmed count");
    }

    @Test
    void beansThatClaimTheSameIdOrNameNothingAreRejected() {
        List<String> checks = List.of("folder_not_empty", "folder_not_empty", "note_not_archived");
        List<String> counts = List.of("notes.folder.share", "notes.folder.delete");

        assertThat(problems(VALID, checks, counts)).containsExactlyInAnyOrder(
                "two PreconditionCheck beans claim the id 'folder_not_empty'",
                "AffectedCount bean for 'notes.folder.delete' names no registered capability");
    }

    @Test
    void theSameIdOnTwoHandlersIsRejected() {
        assertThat(problems(List.of(ShareAndFindHandlers.class, ArchiveHandler.class, ArchiveHandlerForgettingItsSibling.class),
                NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS))
                .anyMatch(problem -> problem.startsWith("notes.note.archive: declared twice"));
    }

    @Test
    void everyShapeProblemIsReportedInOneGo() {
        List<String> problems = problems(List.of(BadlyShaped.class), List.of(), List.of(), List.of());

        assertThat(problems).contains(
                "Notes_Tidy (BadlyShaped.tidy): id must be dotted lower-case, like fee.reminder.send",
                "Notes_Tidy (BadlyShaped.tidy): readOnly must be true exactly when blastRadius is NONE",
                "Notes_Tidy (BadlyShaped.tidy): disambiguateFrom names the capability itself",
                "Notes_Tidy (BadlyShaped.tidy): param 'not_a_field' is not a field of Undocumented",
                "Notes_Tidy (BadlyShaped.tidy): request field 'blob' has a type the planner cannot fill: java.lang.Object",
                "Notes_Tidy (BadlyShaped.tidy): request field 'blob' has no @AgentParam",
                "Notes_Tidy (BadlyShaped.tidy): param 'note_id' must set resolver and label together, or neither",
                "Notes_Tidy (BadlyShaped.tidy): a read creates and notifies nothing, so creates and notifies must be empty",
                "Notes_Tidy (BadlyShaped.tidy): a read needs no confirmation, so its confirmation and pending templates must be empty",
                "Notes_Tidy (BadlyShaped.tidy): confirmation template has a brace that is not a {lower_snake_case} placeholder",
                "Notes_Tidy (BadlyShaped.tidy): pending template may not contain placeholders, because its values are not known yet",
                "notes.note.merge (BadlyShaped.merge): description is empty",
                "notes.note.merge (BadlyShaped.merge): is not a request-mapped controller method",
                "notes.note.merge (BadlyShaped.merge): takes 2 parameters; a capability handler takes at most one request record",
                "notes.note.merge (BadlyShaped.merge): a write needs a confirmation template");
    }

    @Test
    void theExceptionMessageListsEveryProblem() {
        assertThatThrownBy(() -> builder.build(VALID, List.of(), List.of(), List.of()))
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageStartingWith("Agent capability registry is invalid (5 problems):")
                .hasMessageContaining("precondition 'folder_not_empty' has no PreconditionCheck bean")
                .hasMessageContaining("precondition 'note_not_archived' has no PreconditionCheck bean")
                .hasMessageContaining("no AffectedCount bean")
                .hasMessageContaining("param 'folder_id' resolves 'folder', but there is no EntityResolver bean")
                .hasMessageContaining("param 'note_id' resolves 'note', but there is no EntityResolver bean");
    }
}
