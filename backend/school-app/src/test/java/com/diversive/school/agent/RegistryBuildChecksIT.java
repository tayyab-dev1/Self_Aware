package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.registry.CapabilityRegistryException;
import com.diversive.school.SchoolApplication;
import com.diversive.school.support.SchoolPostgresContainer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Phase 1 "done when": the real backend refuses to start when a registry rule breaks, so every test
 * that boots it fails and the build fails, rather than a warning appearing at runtime.
 */
class RegistryBuildChecksIT {

    @Test
    void removingAPreconditionCheckBeanStopsTheBackend() {
        Throwable failure = startBackendWith(context -> context.addBeanFactoryPostProcessor(beanFactory ->
                ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition("sectionHasDefaulters")));

        assertThat(failure).rootCause()
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageContaining("fee.reminder.send: precondition 'section_has_defaulters' has no PreconditionCheck bean");
    }

    @Test
    void removingAnEntityResolverBeanStopsTheBackend() {
        Throwable failure = startBackendWith(context -> context.addBeanFactoryPostProcessor(beanFactory ->
                ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition("sectionResolver")));

        assertThat(failure).rootCause()
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageContaining("fee.reminder.send: param 'section_id' resolves 'section', but there is no EntityResolver bean for it");
    }

    @Test
    void aOneDirectionalDisambiguateFromStopsTheBackend() {
        Throwable failure = startBackendWith(context ->
                ((GenericApplicationContext) context).registerBean(OneSidedSibling.class));

        assertThat(failure).rootCause()
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageContaining("fee.writeoff.reverse: disambiguateFrom names 'fee.overdue.list', "
                        + "but 'fee.overdue.list' does not name 'fee.writeoff.reverse' back");
    }

    /** Names fee.overdue.list as a sibling, which fee.overdue.list does not reciprocate. */
    static class OneSidedSibling {

        @AgentCapability(id = "fee.writeoff.reverse", module = "fee", readOnly = false,
                blastRadius = BlastRadius.SINGLE, description = "Reverses a write-off after the family pays.",
                disambiguateFrom = {"fee.overdue.list"})
        @AgentEffect(confirmationTemplate = "Reverse the write-off.", replyTemplate = "Reversed.")
        @PostMapping("/test/one-sided-sibling")
        public void reverse() {
        }
    }

    private static Throwable startBackendWith(ApplicationContextInitializer<ConfigurableApplicationContext> change) {
        SpringApplication backend = new SpringApplication(SchoolApplication.class);
        backend.addInitializers(change);
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off"));
        SchoolPostgresContainer.applicationProperties().forEach((name, value) -> args.add("--" + name + "=" + value));

        Throwable failure = catchThrowable(() -> backend.run(args.toArray(String[]::new)).close());
        assertThat(failure).as("the backend should have refused to start").isNotNull();
        return failure;
    }
}
