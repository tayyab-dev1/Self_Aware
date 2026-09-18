package com.diversive.school.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that boot the whole backend against a real database.
 * Subclasses share one Spring context and one container, so keep every
 * context-shaping annotation here rather than on a subclass.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

    /** The POC dev token the tests sign in with; it stands for the seeded user sana.iqbal. */
    public static final String DEV_TOKEN = "integration-test-dev-token";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        SchoolPostgresContainer.applicationProperties().forEach((name, value) -> registry.add(name, () -> value));
    }
}
