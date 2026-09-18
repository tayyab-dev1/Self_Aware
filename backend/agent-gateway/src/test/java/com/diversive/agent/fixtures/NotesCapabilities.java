package com.diversive.agent.fixtures;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.AgentPrecondition;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.spi.UserContext;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * A small, school-free domain for testing the gateway on its own: share a folder of notes (a group
 * write with a count), archive one note (a single write), find notes (a read). Share and archive
 * are declared siblings.
 */
public final class NotesCapabilities {

    public static final List<String> CHECK_IDS = List.of("folder_not_empty", "note_not_archived");
    public static final List<String> COUNT_IDS = List.of("notes.folder.share");
    public static final List<String> RESOLVER_TYPES = List.of("folder", "note");

    private NotesCapabilities() {
    }

    public enum Channel {
        EMAIL, SMS;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase();
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShareFolderRequest(@NotNull Long folderId, @NotNull Channel channel, String coverNote) {

        /** A rule across two fields, which only the whole record can check. */
        @AssertTrue(message = "a cover note can only go by email")
        public boolean isCoverNoteByEmail() {
            return coverNote == null || channel != Channel.SMS;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArchiveNoteRequest(@NotNull Long noteId, @NotBlank @Size(max = 40) String reason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindNotesQuery(String text, List<Long> tagIds, LocalDate since, BigDecimal minimumSize,
                                 boolean pinnedOnly) {
    }

    /** What a share did: execute verifies {@code count} against the confirmed count. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShareResult(long count, long sharedBytes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindResult(long count, List<String> titles) {
    }

    /** Share (group write) and find (read). */
    public static class ShareAndFindHandlers {

        private final NotesStore store;

        public ShareAndFindHandlers() {
            this(new NotesStore());
        }

        public ShareAndFindHandlers(NotesStore store) {
            this.store = store;
        }

        @AgentCapability(
                id = "notes.folder.share",
                module = "notes",
                readOnly = false,
                blastRadius = BlastRadius.GROUP,
                description = """
                        Shares every note in a folder with the folder's members.
                        Not for putting one note away - use notes.note.archive.
                        """,
                disambiguateFrom = {"notes.note.archive"})
        @AgentParam(name = "folder_id", meaning = "The folder to share", resolver = "folder", label = "folder_name")
        @AgentParam(name = "channel", meaning = "How members are told", defaultValue = "email")
        @AgentParam(name = "cover_note", meaning = "A short note sent with the share")
        @AgentPrecondition(id = "folder_not_empty", text = "The folder must contain a note",
                hint = "There is nothing in this folder to share")
        @AgentEffect(
                creates = "one share record per note",
                notifies = "the folder's members",
                confirmationTemplate = "Share {count} notes in {folder_name} by {channel}.",
                pendingTemplate = "Share the folder.",
                replyTemplate = "Shared {count} notes from {folder_name}, {shared_bytes} bytes in all.",
                facts = {"shared_bytes"})
        @PostMapping("/fixture/notes/share")
        public ShareResult share(@RequestBody ShareFolderRequest request) {
            long count = store.share(request.folderId(), request.channel().wireValue());
            return new ShareResult(count, count * 512);
        }

        @AgentCapability(
                id = "notes.note.find",
                module = "notes",
                readOnly = true,
                blastRadius = BlastRadius.NONE,
                description = "Finds notes by their text, tags or date.")
        @AgentParam(name = "text", meaning = "Words the note contains")
        @AgentParam(name = "tag_ids", meaning = "Tags the note carries")
        @AgentParam(name = "since", meaning = "Earliest date written")
        @AgentParam(name = "minimum_size", meaning = "Smallest size in kilobytes")
        @AgentParam(name = "pinned_only", meaning = "Only pinned notes")
        @AgentEffect(replyTemplate = "Found {count} notes.")
        @GetMapping("/fixture/notes")
        public FindResult find(FindNotesQuery query) {
            List<String> titles = store.notes.values().stream()
                    .filter(title -> query.text() == null || title.toLowerCase().contains(query.text().toLowerCase()))
                    .toList();
            return new FindResult(titles.size(), titles);
        }
    }

    /** Archive (single write), naming share back. */
    public static class ArchiveHandler {

        private final NotesStore store;

        public ArchiveHandler() {
            this(new NotesStore());
        }

        public ArchiveHandler(NotesStore store) {
            this.store = store;
        }

        @AgentCapability(
                id = "notes.note.archive",
                module = "notes",
                readOnly = false,
                blastRadius = BlastRadius.SINGLE,
                reverses = "notes.note.restore",
                description = "Archives one note so it no longer shows. Not for sharing - use notes.folder.share.",
                disambiguateFrom = {"notes.folder.share"})
        @AgentParam(name = "note_id", meaning = "The note to archive", resolver = "note", label = "note_title")
        @AgentParam(name = "reason", meaning = "Why it is archived")
        @AgentPrecondition(id = "note_not_archived", text = "The note must not be archived already",
                hint = "That note is already archived")
        @AgentEffect(confirmationTemplate = "Archive {note_title}.", replyTemplate = "Archived {note_title}.")
        @PostMapping("/fixture/notes/archive")
        public void archive(@RequestBody ArchiveNoteRequest request) {
            store.archived.add(request.noteId());
        }
    }

    /** Identical to {@link ArchiveHandler} except one character of the description ("shows" to "show"). */
    public static class ArchiveHandlerWithEditedDescription {

        @AgentCapability(
                id = "notes.note.archive",
                module = "notes",
                readOnly = false,
                blastRadius = BlastRadius.SINGLE,
                reverses = "notes.note.restore",
                description = "Archives one note so it no longer show. Not for sharing - use notes.folder.share.",
                disambiguateFrom = {"notes.folder.share"})
        @AgentParam(name = "note_id", meaning = "The note to archive", resolver = "note", label = "note_title")
        @AgentParam(name = "reason", meaning = "Why it is archived")
        @AgentPrecondition(id = "note_not_archived", text = "The note must not be archived already",
                hint = "That note is already archived")
        @AgentEffect(confirmationTemplate = "Archive {note_title}.", replyTemplate = "Archived {note_title}.")
        @PostMapping("/fixture/notes/archive")
        public void archive(@RequestBody ArchiveNoteRequest request) {
        }
    }

    /** Archive without naming share back: makes disambiguateFrom one-directional. */
    public static class ArchiveHandlerForgettingItsSibling {

        @AgentCapability(
                id = "notes.note.archive",
                module = "notes",
                readOnly = false,
                blastRadius = BlastRadius.SINGLE,
                description = "Archives one note so it no longer shows.")
        @AgentParam(name = "note_id", meaning = "The note to archive", resolver = "note", label = "note_title")
        @AgentParam(name = "reason", meaning = "Why it is archived")
        @AgentPrecondition(id = "note_not_archived", text = "The note must not be archived already",
                hint = "That note is already archived")
        @AgentEffect(confirmationTemplate = "Archive {note_title}.", replyTemplate = "Archived {note_title}.")
        @PostMapping("/fixture/notes/archive")
        public void archive(@RequestBody ArchiveNoteRequest request) {
        }
    }

    public record FixedCheck(String id) implements PreconditionCheck {

        @Override
        public boolean holds(Map<String, Object> resolvedParams, UserContext user) {
            return true;
        }
    }

    public record FixedCount(String capabilityId) implements AffectedCount {

        @Override
        public CountResult count(Map<String, Object> resolvedParams, UserContext user) {
            return new CountResult(1, "notes", Map.of());
        }
    }
}
