package com.diversive.agent.config;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.error.AgentGatewayExceptionHandler;
import com.diversive.agent.error.AgentRequestBodyExceptionHandler;
import com.diversive.agent.execute.CapabilityHandlers;
import com.diversive.agent.execute.ExecuteController;
import com.diversive.agent.execute.ExecuteProperties;
import com.diversive.agent.execute.ExecuteService;
import com.diversive.agent.metadata.AgentMetadataController;
import com.diversive.agent.preflight.PreflightController;
import com.diversive.agent.preflight.PreflightProperties;
import com.diversive.agent.preflight.PreflightService;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.diversive.agent.session.AgentSessionController;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.AuditTrail;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.spi.TemplateFormatter;
import com.diversive.agent.spi.UserContextResolver;
import com.diversive.agent.step.CapabilityBeans;
import com.diversive.agent.step.ParamBinder;
import com.diversive.agent.step.PlainTemplateFormatter;
import com.diversive.agent.step.PlanReader;
import com.diversive.agent.step.StepPasses;
import com.diversive.agent.token.PreflightTokens;
import com.diversive.agent.web.UserContextArgumentResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Plugs the agent gateway into any Spring Boot web application that has this jar on its classpath.
 *
 * <p>At startup it finds every bean class with {@code @AgentCapability} methods, builds the registry
 * from them, and checks the rules against the application's {@link PreconditionCheck},
 * {@link AffectedCount} and {@link EntityResolver} beans. A broken rule stops the application, which
 * fails every test that boots it, so the build fails rather than a log line appearing at runtime.
 *
 * <p>The host application supplies a {@code UserContextResolver}, a {@code CapabilityPolicy} and an
 * {@link AuditTrail}, and may supply a {@link TemplateFormatter} and a {@link Clock}.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class, afterName = {
        "org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({PreflightProperties.class, ExecuteProperties.class})
@Import({AgentMetadataController.class, AgentSessionController.class, PreflightController.class, ExecuteController.class,
        AgentGatewayExceptionHandler.class, AgentRequestBodyExceptionHandler.class})
public class AgentGatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayAutoConfiguration.class);

    @Bean
    CapabilityRegistry capabilityRegistry(ConfigurableListableBeanFactory beanFactory,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<PreconditionCheck> preconditionChecks,
                                          ObjectProvider<AffectedCount> affectedCounts,
                                          ObjectProvider<EntityResolver> entityResolvers) {
        List<Class<?>> handlerTypes = capabilityHandlerTypes(beanFactory);
        List<String> checkIds = preconditionChecks.orderedStream().map(PreconditionCheck::id).toList();
        List<String> countIds = affectedCounts.orderedStream().map(AffectedCount::capabilityId).toList();
        List<String> resolverTypes = entityResolvers.orderedStream().map(EntityResolver::type).toList();
        Map<String, String> lookups = new LinkedHashMap<>();
        entityResolvers.orderedStream()
                .filter(resolver -> resolver.lookup() != null && !resolver.lookup().isBlank())
                .forEach(resolver -> lookups.putIfAbsent(resolver.type(), resolver.lookup().strip()));

        CapabilityRegistry registry = new CapabilityRegistryBuilder(objectMapper)
                .build(handlerTypes, checkIds, countIds, resolverTypes, lookups);
        log.info("Agent capability registry: {} capabilities {}", registry.size(), registry.ids());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    TemplateFormatter templateFormatter(ObjectMapper objectMapper) {
        return new PlainTemplateFormatter(objectMapper);
    }

    @Bean
    PreflightTokens preflightTokens(PreflightProperties properties, ObjectProvider<Clock> clock) {
        return PreflightTokens.create(properties.tokenSecret(), properties.tokenTtl(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Reads plans against the registry. The registry is a parameter so it is built, and its rules enforced,
     * before any plan can be read. The application must reject unknown JSON fields, or it does not start.
     */
    @Bean
    PlanReader agentPlanReader(CapabilityRegistry registry, CapabilityPolicy policy, ObjectMapper objectMapper,
                               ObjectProvider<Validator> validator, PreflightProperties properties) {
        if (!objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
            throw new IllegalStateException("The agent gateway rejects JSON fields its contract does not define, so the "
                    + "application must set spring.jackson.deserialization.fail-on-unknown-properties=true");
        }
        return new PlanReader(registry, policy, new ParamBinder(objectMapper, validator.getIfAvailable()),
                properties.maxSteps());
    }

    /** The passes preflight and execute both run: resolve, validate, check, count, fill templates. */
    @Bean
    StepPasses agentStepPasses(ObjectProvider<PreconditionCheck> preconditionChecks,
                               ObjectProvider<AffectedCount> affectedCounts,
                               ObjectProvider<EntityResolver> entityResolvers,
                               TemplateFormatter templateFormatter,
                               ObjectMapper objectMapper,
                               ObjectProvider<Validator> validator) {
        CapabilityBeans beans = CapabilityBeans.of(preconditionChecks.orderedStream().toList(),
                affectedCounts.orderedStream().toList(), entityResolvers.orderedStream().toList());
        return new StepPasses(beans, new ParamBinder(objectMapper, validator.getIfAvailable()), templateFormatter);
    }

    /** The database passes share one read-only, repeatable-read transaction when the application has a transaction manager. */
    @Bean
    PreflightService preflightService(PlanReader agentPlanReader, StepPasses agentStepPasses, PreflightTokens preflightTokens,
                                      ObjectProvider<PlatformTransactionManager> transactionManager) {
        return new PreflightService(agentPlanReader, agentStepPasses, preflightTokens,
                snapshot(transactionManager.getIfAvailable()));
    }

    /**
     * Each step runs in a serializable transaction of its own, and a refusal is recorded in a new one after its
     * step rolled back. The application must supply an {@link AuditTrail}: nothing is executed unrecorded.
     */
    @Bean
    ExecuteService executeService(CapabilityRegistry registry, PlanReader agentPlanReader, StepPasses agentStepPasses,
                                  PreflightTokens preflightTokens, ObjectProvider<AuditTrail> auditTrail,
                                  ConfigurableListableBeanFactory beanFactory, ObjectMapper objectMapper,
                                  ObjectProvider<PlatformTransactionManager> transactionManager,
                                  ExecuteProperties properties) {
        AuditTrail audit = auditTrail.getIfAvailable();
        if (audit == null) {
            throw new IllegalStateException("The agent gateway records every execution, so the application must supply "
                    + "an AuditTrail bean");
        }
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        return new ExecuteService(registry, agentPlanReader, agentStepPasses, preflightTokens, audit,
                new CapabilityHandlers(beanFactory::getBean), objectMapper, serializable(manager), newTransaction(manager),
                properties);
    }

    /** Lets a capability handler take the signed-in {@code UserContext} when it is called as a plain endpoint. */
    @Bean
    WebMvcConfigurer agentUserContextArguments(ObjectProvider<UserContextResolver> userContextResolver) {
        UserContextArgumentResolver resolver = new UserContextArgumentResolver(userContextResolver);
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }

    private static TransactionOperations snapshot(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return TransactionOperations.withoutTransaction();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    private static TransactionOperations serializable(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return TransactionOperations.withoutTransaction();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return template;
    }

    private static TransactionOperations newTransaction(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return TransactionOperations.withoutTransaction();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Bean classes declaring at least one {@code @AgentCapability} method, found without creating the beans. */
    private static List<Class<?>> capabilityHandlerTypes(ConfigurableListableBeanFactory beanFactory) {
        List<Class<?>> types = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            boolean declaresCapability = Arrays.stream(userType.getMethods())
                    .anyMatch(method -> method.isAnnotationPresent(AgentCapability.class));
            if (declaresCapability && !types.contains(userType)) {
                types.add(userType);
            }
        }
        return types;
    }
}
