package com.diversive.agent.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.fixtures.NotesCapabilities;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandlerWithEditedDescription;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.metadata.CapabilityVersion;
import com.diversive.agent.metadata.EffectMetadata;
import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.metadata.ParamType;
import com.diversive.agent.metadata.PreconditionMetadata;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CapabilityRegistryBuilderTest {

    private final CapabilityRegistryBuilder builder = new CapabilityRegistryBuilder(JsonMapper.builder().build());

    private CapabilityRegistry build(Class<?>... handlers) {
        return builder.build(List.of(handlers), NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS,
                    NotesCapabilities.RESOLVER_TYPES);
    }

    @Test
    void aResolversLookupIsPublishedWithEveryParameterItFindsAndIsPartOfTheVersion() {
        Map<String, String> lookups = Map.of("folder", "the folder's name, e.g. travel plans");
        CapabilityRegistry described = builder.build(List.of(ShareAndFindHandlers.class, ArchiveHandler.class),
                NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS, NotesCapabilities.RESOLVER_TYPES, lookups);
        CapabilityMetadata share = described.find("notes.folder.share").orElseThrow().metadata();
        CapabilityMetadata plain = build(ShareAndFindHandlers.class, ArchiveHandler.class)
                .find("notes.folder.share").orElseThrow().metadata();

        assertThat(share.params()).filteredOn(param -> param.name().equals("folder_id"))
                .extracting(ParamMetadata::lookup).containsExactly("the folder's name, e.g. travel plans");
        assertThat(share.params()).filteredOn(param -> param.resolver() == null)
                .extracting(ParamMetadata::lookup).containsOnlyNulls();
        assertThat(share.version()).isNotEqualTo(plain.version());
        // A resolver that says nothing leaves its parameters as they were.
        assertThat(described.find("notes.note.archive").orElseThrow().metadata().version())
                .isEqualTo(build(ShareAndFindHandlers.class, ArchiveHandler.class)
                        .find("notes.note.archive").orElseThrow().metadata().version());
    }

    @Test
    void buildsEveryEntrySortedById() {
        CapabilityRegistry registry = build(ShareAndFindHandlers.class, ArchiveHandler.class);

        assertThat(registry.ids()).containsExactly("notes.folder.share", "notes.note.archive", "notes.note.find");
    }

    @Test
    void readsFullDetailFromTheAnnotationsAndTheRequestRecord() {
        CapabilityMetadata share = build(ShareAndFindHandlers.class, ArchiveHandler.class)
                .find("notes.folder.share").orElseThrow().metadata();

        assertThat(share.module()).isEqualTo("notes");
        assertThat(share.readOnly()).isFalse();
        assertThat(share.blastRadius()).isEqualTo(BlastRadius.GROUP);
        assertThat(share.reverses()).isNull();
        assertThat(share.description()).isEqualTo(
                "Shares every note in a folder with the folder's members. "
                        + "Not for putting one note away - use notes.note.archive.");
        assertThat(share.disambiguateFrom()).containsExactly("notes.note.archive");
        assertThat(share.params()).containsExactly(
                new ParamMetadata("folder_id", ParamType.INTEGER, false, true, "The folder to share",
                        "folder", "folder_name", null, List.of(), null),
                new ParamMetadata("channel", ParamType.STRING, false, true, "How members are told",
                        null, null, null, List.of("email", "sms"), "email"),
                new ParamMetadata("cover_note", ParamType.STRING, false, false, "A short note sent with the share",
                        null, null, null, List.of(), null));
        assertThat(share.preconditions()).containsExactly(new PreconditionMetadata(
                "folder_not_empty", "The folder must contain a note", "There is nothing in this folder to share"));
        assertThat(share.effect()).isEqualTo(new EffectMetadata(
                "one share record per note",
                "the folder's members",
                "Share {count} notes in {folder_name} by {channel}.",
                "Share the folder.",
                "Shared {count} notes from {folder_name}, {shared_bytes} bytes in all.",
                List.of("shared_bytes")));
    }

    @Test
    void readsListsDatesDecimalsAndBooleansFromARead() {
        CapabilityMetadata find = build(ShareAndFindHandlers.class, ArchiveHandler.class)
                .find("notes.note.find").orElseThrow().metadata();

        assertThat(find.params())
                .extracting(ParamMetadata::name, ParamMetadata::type, ParamMetadata::multiple, ParamMetadata::required)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("text", ParamType.STRING, false, false),
                        org.assertj.core.groups.Tuple.tuple("tag_ids", ParamType.INTEGER, true, false),
                        org.assertj.core.groups.Tuple.tuple("since", ParamType.DATE, false, false),
                        org.assertj.core.groups.Tuple.tuple("minimum_size", ParamType.DECIMAL, false, false),
                        org.assertj.core.groups.Tuple.tuple("pinned_only", ParamType.BOOLEAN, false, true));
        assertThat(find.effect().confirmationTemplate()).isNull();
        assertThat(find.blastRadius()).isEqualTo(BlastRadius.NONE);
    }

    @Test
    void theVersionIsAStableSha256() {
        List<CapabilityVersion> first = build(ShareAndFindHandlers.class, ArchiveHandler.class).versions();
        List<CapabilityVersion> second = build(ArchiveHandler.class, ShareAndFindHandlers.class).versions();

        assertThat(first).allSatisfy(version -> assertThat(version.version()).matches("[0-9a-f]{64}"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void changingOneCharacterOfADescriptionChangesOnlyThatEntrysVersion() {
        Map<String, String> before = versionsById(build(ShareAndFindHandlers.class, ArchiveHandler.class));
        Map<String, String> after = versionsById(build(ShareAndFindHandlers.class, ArchiveHandlerWithEditedDescription.class));

        assertThat(after.get("notes.note.archive")).isNotEqualTo(before.get("notes.note.archive"));
        assertThat(after.get("notes.folder.share")).isEqualTo(before.get("notes.folder.share"));
        assertThat(after.get("notes.note.find")).isEqualTo(before.get("notes.note.find"));
    }

    @Test
    void theVersionCoversEveryFieldButNotItself() {
        CapabilityMetadata archive = build(ShareAndFindHandlers.class, ArchiveHandler.class)
                .find("notes.note.archive").orElseThrow().metadata();

        CapabilityMetadata otherHint = new CapabilityMetadata(archive.id(), archive.version(), archive.module(),
                archive.readOnly(), archive.blastRadius(), archive.reverses(), archive.description(),
                archive.disambiguateFrom(), archive.params(),
                List.of(new PreconditionMetadata("note_not_archived", "The note must not be archived already",
                        "That note is archived already")),
                archive.effect());

        assertThat(CapabilityVersioner.version(archive)).isEqualTo(archive.version());
        assertThat(CapabilityVersioner.version(archive.withVersion("anything"))).isEqualTo(archive.version());
        assertThat(CapabilityVersioner.version(otherHint)).isNotEqualTo(archive.version());
    }

    @Test
    void remembersTheHandlerWithoutPublishingIt() {
        RegisteredCapability archive = build(ShareAndFindHandlers.class, ArchiveHandler.class)
                .find("notes.note.archive").orElseThrow();

        assertThat(archive.handlerType()).isEqualTo(ArchiveHandler.class);
        assertThat(archive.handlerMethod().getName()).isEqualTo("archive");
        assertThat(archive.requestType()).isEqualTo(NotesCapabilities.ArchiveNoteRequest.class);
    }

    private static Map<String, String> versionsById(CapabilityRegistry registry) {
        return registry.versions().stream().collect(Collectors.toMap(CapabilityVersion::id, CapabilityVersion::version));
    }
}
