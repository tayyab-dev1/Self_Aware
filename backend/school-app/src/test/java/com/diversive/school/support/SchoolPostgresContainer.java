package com.diversive.school.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres + pgvector container shared by every integration test in the JVM.
 *
 * <p>It runs the same init script as docker compose, so tests connect as the
 * least-privileged backend role into the "school" schema, exactly like local dev.
 */
public final class SchoolPostgresContainer {

    public static final String BACKEND_USER = "sms_backend";
    public static final String BACKEND_PASSWORD = "backend_test_password";
    public static final String AI_LAYER_USER = "sms_ai_layer";
    public static final String AI_LAYER_PASSWORD = "ai_layer_test_password";

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres");

    private static final Path INIT_SCRIPT =
            Path.of("..", "..", "docker", "postgres", "initdb", "01-roles-schemas-extensions.sh");

    private static final PostgreSQLContainer<?> CONTAINER = createAndStart();

    private SchoolPostgresContainer() {
    }

    /** JDBC URL for the backend role, pointed at the "school" schema like application.yml. */
    public static String backendJdbcUrl() {
        return CONTAINER.getJdbcUrl() + (CONTAINER.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=school,public";
    }

    /** Properties that point a booting backend at this container and sign tests in with the dev token. */
    public static java.util.Map<String, String> applicationProperties() {
        return java.util.Map.of(
                "spring.datasource.url", backendJdbcUrl(),
                "spring.datasource.username", BACKEND_USER,
                "spring.datasource.password", BACKEND_PASSWORD,
                "school.agent.dev-user.token", PostgresIntegrationTest.DEV_TOKEN,
                "school.agent.dev-user.username", "sana.iqbal");
    }

    @SuppressWarnings("resource") // closed by Testcontainers' Ryuk when the JVM exits
    private static PostgreSQLContainer<?> createAndStart() {
        if (!Files.isRegularFile(INIT_SCRIPT)) {
            throw new IllegalStateException("Database init script not found at " + INIT_SCRIPT.toAbsolutePath());
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("sms")
                .withEnv("BACKEND_DB_USER", BACKEND_USER)
                .withEnv("BACKEND_DB_PASSWORD", BACKEND_PASSWORD)
                .withEnv("AI_LAYER_INDEX_DB_USER", AI_LAYER_USER)
                .withEnv("AI_LAYER_INDEX_DB_PASSWORD", AI_LAYER_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(INIT_SCRIPT, 0755),
                        "/docker-entrypoint-initdb.d/01-roles-schemas-extensions.sh");
        container.start();
        return container;
    }
}
