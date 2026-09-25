package com.diversive.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.agent.execute.ExecuteController;
import com.diversive.agent.execute.ExecuteService;
import com.diversive.agent.fixtures.MemoryAuditTrail;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandlerForgettingItsSibling;
import com.diversive.agent.fixtures.NotesCapabilities.FixedCheck;
import com.diversive.agent.fixtures.NotesCapabilities.FixedCount;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.metadata.AgentMetadataController;
import com.diversive.agent.preflight.PreflightController;
import com.diversive.agent.preflight.PreflightService;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryException;
import com.diversive.agent.session.AgentSessionController;
import com.diversive.agent.spi.AuditTrail;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.TemplateFormatter;
import com.diversive.agent.spi.UserContextResolver;
import com.diversive.agent.step.PlainTemplateFormatter;
import com.diversive.agent.token.PreflightTokens;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * The gateway inside a running application: a broken rule stops the application from starting,
 * which is what fails the build.
 */
class AgentGatewayAutoConfigurationTest {

    /** Everything a valid application needs, except the archive handler and the beans behind it. */
    private final WebApplicationContextRunner application = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, AgentGatewayAutoConfiguration.class))
            .withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=true")
            .withBean(ShareAndFindHandlers.class)
            .withBean("folderNotEmpty", FixedCheck.class, () -> new FixedCheck("folder_not_empty"))
            .withBean("shareCount", FixedCount.class, () -> new FixedCount("notes.folder.share"))
            .withBean("folderResolver", EntityResolver.class, () -> EntityResolver.of("folder", (lookup, user) -> List.of()))
            .withBean(UserContextResolver.class, () -> request -> Optional.empty())
            .withBean(CapabilityPolicy.class, () -> (user, capabilityId) -> true)
            .withBean(AuditTrail.class, MemoryAuditTrail::new);

    private static WebApplicationContextRunner withArchiveBeans(WebApplicationContextRunner runner) {
        return withNoteResolver(runner).withBean("noteNotArchived", FixedCheck.class, () -> new FixedCheck("note_not_archived"));
    }

    private static WebApplicationContextRunner withNoteResolver(WebApplicationContextRunner runner) {
        return runner.withBean("noteResolver", EntityResolver.class, () -> EntityResolver.of("note", (lookup, user) -> List.of()));
    }

    @Test
    void buildsTheRegistryAndPreflightFromTheApplicationsBeans() {
        withArchiveBeans(application).withBean(ArchiveHandler.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CapabilityRegistry.class).ids())
                    .containsExactly("notes.folder.share", "notes.note.archive", "notes.note.find");
            assertThat(context).hasSingleBean(AgentMetadataController.class);
            assertThat(context).hasSingleBean(AgentSessionController.class);
            assertThat(context).hasSingleBean(PreflightController.class);
            assertThat(context).hasSingleBean(PreflightService.class);
            assertThat(context).hasSingleBean(PreflightTokens.class);
            assertThat(context).hasSingleBean(ExecuteController.class);
            assertThat(context).hasSingleBean(ExecuteService.class);
            assertThat(context.getBean(TemplateFormatter.class)).isInstanceOf(PlainTemplateFormatter.class);
            assertThat(context.getBean(PreflightTokens.class).ttl()).hasMinutes(5);
        });
    }

    @Test
    void removingAPreconditionCheckBeanStopsTheApplication() {
        withNoteResolver(application).withBean(ArchiveHandler.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(CapabilityRegistryException.class)
                    .hasMessageContaining("notes.note.archive: precondition 'note_not_archived' has no PreconditionCheck bean");
        });
    }

    @Test
    void removingAnEntityResolverBeanStopsTheApplication() {
        application.withBean("noteNotArchived", FixedCheck.class, () -> new FixedCheck("note_not_archived"))
                .withBean(ArchiveHandler.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(CapabilityRegistryException.class)
                            .hasMessageContaining("notes.note.archive: param 'note_id' resolves 'note', but there is no "
                                    + "EntityResolver bean for it");
                });
    }

    @Test
    void oneDirectionalDisambiguateFromStopsTheApplication() {
        withArchiveBeans(application).withBean(ArchiveHandlerForgettingItsSibling.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(CapabilityRegistryException.class)
                    .hasMessageContaining("disambiguateFrom must be symmetric");
        });
    }

    @Test
    void aTokenSecretTooShortToBeSafeStopsTheApplication() {
        withArchiveBeans(application).withBean(ArchiveHandler.class)
                .withPropertyValues("agent.gateway.preflight.token-secret=change-me")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("at least 32 bytes");
                });
    }

    @Test
    void anApplicationThatAcceptsUnknownJsonFieldsDoesNotStart() {
        withArchiveBeans(application).withBean(ArchiveHandler.class)
                .withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("spring.jackson.deserialization.fail-on-unknown-properties=true");
                });
    }

    @Test
    void anApplicationWithoutAnAuditTrailDoesNotStart() {
        withArchiveBeans(new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, AgentGatewayAutoConfiguration.class))
                .withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=true")
                .withBean(ShareAndFindHandlers.class)
                .withBean("folderNotEmpty", FixedCheck.class, () -> new FixedCheck("folder_not_empty"))
                .withBean("shareCount", FixedCount.class, () -> new FixedCount("notes.folder.share"))
                .withBean("folderResolver", EntityResolver.class, () -> EntityResolver.of("folder", (lookup, user) -> List.of()))
                .withBean(UserContextResolver.class, () -> request -> Optional.empty())
                .withBean(CapabilityPolicy.class, () -> (user, capabilityId) -> true))
                .withBean(ArchiveHandler.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("must supply an AuditTrail bean");
                });
    }

    @Test
    void staysOutOfNonWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, AgentGatewayAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CapabilityRegistry.class));
    }
}
