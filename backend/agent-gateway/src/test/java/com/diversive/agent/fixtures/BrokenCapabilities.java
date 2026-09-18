package com.diversive.agent.fixtures;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentNotImplemented;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.BlastRadius;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Handlers that each break rules on purpose, so the tests can show every rule is enforced. */
public final class BrokenCapabilities {

    private BrokenCapabilities() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Undocumented(Long noteId, Object blob) {
    }

    public static class TemplateWithUnknownPlaceholder {

        @AgentCapability(id = "notes.note.pin", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Pins a note to the top.")
        @AgentEffect(confirmationTemplate = "Pin {count} note for {mystery}.", replyTemplate = "Pinned.")
        @PostMapping("/fixture/notes/pin")
        public void pin() {
        }
    }

    public static class BadlyShaped {

        /** Bad id, read-only with a blast radius, a read that confirms, an undocumented field, a stray param. */
        @AgentCapability(id = "Notes_Tidy", module = "notes", readOnly = true, blastRadius = BlastRadius.GROUP,
                description = "Tidies notes.", disambiguateFrom = {"Notes_Tidy"})
        @AgentParam(name = "note_id", meaning = "The note", resolver = "note")
        @AgentParam(name = "not_a_field", meaning = "Nothing")
        @AgentEffect(creates = "nothing", confirmationTemplate = "Tidy it {oops", pendingTemplate = "Tidy {note_id}.",
                replyTemplate = "Tidied.")
        @PostMapping("/fixture/notes/tidy")
        public void tidy(@RequestBody Undocumented request) {
        }

        /** A write with no confirmation, no effect-level reply, two parameters and no request mapping. */
        @AgentCapability(id = "notes.note.merge", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = " ")
        @AgentEffect(replyTemplate = "Merged.")
        public void merge(Long first, Long second) {
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LabelNoteRequest(@NotNull Long noteId, String colour, Long otherNoteId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LabelResult(long labelCount) {
    }

    /** A confirmation that could show a gap: an optional value, an optional record's label, and a fact with no count. */
    public static class ConfirmationWithGaps {

        @AgentCapability(id = "notes.note.label", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Labels a note with a colour.")
        @AgentParam(name = "note_id", meaning = "The note", resolver = "note", label = "note_title")
        @AgentParam(name = "colour", meaning = "The label colour")
        @AgentParam(name = "other_note_id", meaning = "A note to copy the label from", resolver = "note", label = "other_title")
        @AgentEffect(confirmationTemplate = "Label {note_title} {colour} like {other_title}, {label_count} so far.",
                replyTemplate = "Labelled.", facts = {"label_count"})
        @PostMapping("/fixture/notes/label")
        public LabelResult label(@RequestBody LabelNoteRequest request) {
            return new LabelResult(1);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TagQuery(List<Long> tagIds) {
    }

    /** Looks up a list of names, which preflight cannot do yet. */
    public static class ListOfNamesToLookUp {

        @AgentCapability(id = "notes.note.tag", module = "notes", readOnly = true, blastRadius = BlastRadius.NONE,
                description = "Finds notes by tag names.")
        @AgentParam(name = "tag_ids", meaning = "The tags", resolver = "tag", label = "tag_names")
        @AgentEffect(replyTemplate = "Found them.")
        @GetMapping("/fixture/notes/by-tag")
        public void tag(TagQuery query) {
        }
    }

    /** Not implemented, and still held to the rules about its published metadata. */
    public static class NotImplementedWithUnknownPlaceholder {

        @AgentCapability(id = "notes.note.shred", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Shreds a note for good.")
        @AgentNotImplemented
        @AgentEffect(confirmationTemplate = "Shred {mystery}.", replyTemplate = "Shredded.")
        @PostMapping("/fixture/notes/shred")
        public void shred() {
        }
    }

    public record StarResult(long stars) {
    }

    /** Handlers that return too little for execute: a declared fact that is not returned, and no count to verify. */
    public static class ReturnsTooLittle {

        @AgentCapability(id = "notes.note.star", module = "notes", readOnly = false, blastRadius = BlastRadius.SINGLE,
                description = "Stars a note.")
        @AgentEffect(confirmationTemplate = "Star the note.", replyTemplate = "Starred, {star_count} stars now.",
                facts = {"star_count"})
        @PostMapping("/fixture/notes/star")
        public StarResult star() {
            return new StarResult(1);
        }

        @AgentCapability(id = "notes.folder.tidy", module = "notes", readOnly = false, blastRadius = BlastRadius.GROUP,
                description = "Tidies a folder.")
        @AgentEffect(confirmationTemplate = "Tidy {count} notes.", replyTemplate = "Tidied.")
        @PostMapping("/fixture/notes/tidy-folder")
        public void tidy() {
        }
    }
}
