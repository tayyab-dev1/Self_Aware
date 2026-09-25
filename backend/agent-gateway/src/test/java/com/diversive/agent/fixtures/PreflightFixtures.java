package com.diversive.agent.fixtures;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentNotImplemented;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.AgentPrecondition;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.spi.UserContext;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The notes domain with data behind it (a {@link NotesStore}), for preflight and execute tests: resolvers,
 * checks and counts that read the store, and handlers that change it. Nothing here knows about schools.
 */
public final class PreflightFixtures {

    public static final UserContext EDITOR = new UserContext("user-7", Set.of("editor"), Map.of("workspace", "1"));
    public static final UserContext READER = new UserContext("user-8", Set.of("reader"), Map.of("workspace", "1"));

    /** Editors may use everything; readers may only find. */
    public static final CapabilityPolicy POLICY = (user, capabilityId) -> user.hasRole("editor") || capabilityId.endsWith(".find");

    private PreflightFixtures() {
    }

    public static List<Class<?>> handlers() {
        return List.of(ShareAndFindHandlers.class, ArchiveHandler.class, RestoreHandler.class, CopyAndPinHandlers.class,
                SweepHandler.class);
    }

    /** One handler instance per handler type, all working on the same store. */
    public static Map<Class<?>, Object> handlerInstances(NotesStore store) {
        Map<Class<?>, Object> instances = new LinkedHashMap<>();
        instances.put(ShareAndFindHandlers.class, new ShareAndFindHandlers(store));
        instances.put(ArchiveHandler.class, new ArchiveHandler(store));
        instances.put(RestoreHandler.class, new RestoreHandler());
        instances.put(CopyAndPinHandlers.class, new CopyAndPinHandlers(store));
        instances.put(SweepHandler.class, new SweepHandler());
        return instances;
    }

    public static List<PreconditionCheck> checks() {
        return checks(new NotesStore());
    }

    public static List<PreconditionCheck> checks(NotesStore store) {
        return List.of(
                PreconditionCheck.of("folder_not_empty", (params, user) -> store.notesInFolder.get((Long) params.get("folder_id")) > 0),
                PreconditionCheck.of("note_not_archived", (params, user) -> !store.archived.contains((Long) params.get("note_id"))));
    }

    public static List<AffectedCount> counts() {
        return counts(new NotesStore());
    }

    public static List<AffectedCount> counts(NotesStore store) {
        return List.of(
                AffectedCount.of("notes.folder.share", (params, user) -> {
                    long notes = store.notesInFolder.get((Long) params.get("folder_id"));
                    return new CountResult(notes, "notes", Map.of("shared_bytes", notes * 512));
                }),
                AffectedCount.of("notes.library.sweep", (params, user) -> new CountResult(12, "notes", Map.of())));
    }

    public static List<EntityResolver> resolvers() {
        return resolvers(new NotesStore());
    }

    public static List<EntityResolver> resolvers(NotesStore store) {
        return List.of(
                EntityResolver.of("folder", (lookup, user) -> search(store.folders, lookup.raw(), "folder")),
                EntityResolver.of("note", (lookup, user) -> search(store.notes, lookup.raw(), "note")));
    }

    public static List<String> checkIds() {
        return checks().stream().map(PreconditionCheck::id).toList();
    }

    public static List<String> countIds() {
        return counts().stream().map(AffectedCount::capabilityId).toList();
    }

    public static List<String> resolverTypes() {
        return resolvers().stream().map(EntityResolver::type).toList();
    }

    /** Every word of the name appears in the record's name, ignoring case. */
    private static List<EntityMatch> search(Map<Long, String> records, String raw, String context) {
        List<String> words = Arrays.stream(raw.toLowerCase(Locale.ROOT).split("\\s+")).filter(word -> !word.isEmpty()).toList();
        return records.entrySet().stream()
                .filter(record -> words.stream().allMatch(record.getValue().toLowerCase(Locale.ROOT)::contains))
                .map(record -> new EntityMatch(String.valueOf(record.getKey()), record.getValue(), context))
                .toList();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NoteRequest(@NotNull Long noteId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CopyResult(long copyId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SweepResult(long count) {
    }

    /** Declared so it can be planned, but it cannot run: preflight refuses it before looking anything up. */
    public static class RestoreHandler {

        @AgentCapability(id = "notes.note.restore", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Brings an archived note back.")
        @AgentNotImplemented
        @AgentParam(name = "note_id", meaning = "The archived note", resolver = "archived_note", label = "note_title")
        @AgentPrecondition(id = "note_is_archived", text = "The note must be archived", hint = "That note is not archived")
        @AgentEffect(confirmationTemplate = "Restore {note_title}.", pendingTemplate = "Restore the note.",
                replyTemplate = "Restored {note_title}.")
        @PostMapping("/fixture/notes/restore")
        public void restore(@RequestBody NoteRequest request) {
        }
    }

    /** Copy publishes the new note's id; pin can take it, so a plan can pin a copy that does not exist yet. */
    public static class CopyAndPinHandlers {

        private final NotesStore store;

        public CopyAndPinHandlers() {
            this(new NotesStore());
        }

        public CopyAndPinHandlers(NotesStore store) {
            this.store = store;
        }

        @AgentCapability(id = "notes.note.copy", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Copies a note.")
        @AgentParam(name = "note_id", meaning = "The note to copy", resolver = "note", label = "note_title")
        @AgentEffect(confirmationTemplate = "Copy {note_title}.", pendingTemplate = "Copy the note.",
                replyTemplate = "Copied {note_title} as note {copy_id}.", facts = {"copy_id"})
        @PostMapping("/fixture/notes/copy")
        public CopyResult copy(@RequestBody NoteRequest request, UserContext user) {
            return new CopyResult(store.copy(request.noteId()));
        }

        @AgentCapability(id = "notes.note.pin", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                reverses = "notes.note.unpin", description = "Pins a note to the top.")
        @AgentParam(name = "note_id", meaning = "The note to pin", resolver = "note", label = "note_title")
        @AgentEffect(confirmationTemplate = "Pin {note_title}.", pendingTemplate = "Pin the new copy.",
                replyTemplate = "Pinned note {note_id}.")
        @PostMapping("/fixture/notes/pin")
        public void pin(@RequestBody NoteRequest request) {
            store.pinned.add(request.noteId());
        }
    }

    /** Touches every old note in the branch's library. */
    public static class SweepHandler {

        @AgentCapability(id = "notes.library.sweep", module = "notes", readOnly = false, blastRadius = BlastRadius.BRANCH,
                description = "Removes every old note from the library.")
        @AgentEffect(confirmationTemplate = "Sweep {count} old notes out of the library.", replyTemplate = "Swept {count} notes.")
        @PostMapping("/fixture/notes/sweep")
        public SweepResult sweep() {
            return new SweepResult(12);
        }
    }
}
