package com.cvcompass.backend;

import java.sql.DriverManager;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One database per integration test class isolates the fixed module schemas. */
final class IsolatedPostgresSchema implements AutoCloseable {
    private final String database =
            "cv_persistence_test_" + UUID.randomUUID().toString().replace("-", "");
    private final String adminUrl =
            System.getenv()
                    .getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/cv_compass");
    private final String username = System.getenv().getOrDefault("TEST_DB_USERNAME", "cvcompass");
    private final String password = System.getenv("TEST_DB_PASSWORD");
    private final String url = isolatedUrl(adminUrl, database);
    private boolean created;
    private java.nio.file.Path storageRoot;

    java.nio.file.Path storageRoot() {
        return storageRoot;
    }

    String databaseName() {
        return database;
    }

    String jdbcUrl() {
        return url;
    }

    private static String isolatedUrl(String adminUrl, String database) {
        // Keep the authority (including multi-host URLs) and connection parameters intact.
        int parameters = adminUrl.indexOf('?');
        int end = parameters < 0 ? adminUrl.length() : parameters;
        int separator = adminUrl.lastIndexOf('/', end - 1);
        if (!adminUrl.startsWith("jdbc:postgresql://")
                || separator < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException("TEST_DB_URL must name a PostgreSQL database");
        }
        return adminUrl.substring(0, separator + 1) + database + adminUrl.substring(end);
    }

    void configure(DynamicPropertyRegistry registry) throws Exception {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Set TEST_DB_PASSWORD for isolated PostgreSQL integration tests");
        }
        if (!created) {
            try (var connection = DriverManager.getConnection(adminUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + database + "\"");
                created = true;
            }
        }
        if (storageRoot == null) {
            storageRoot = java.nio.file.Files.createTempDirectory("cv-storage-test-");
        }
        registry.add("cv.storage.provider", () -> "local");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("cv.storage.local.root-directory", () -> storageRoot.toString());
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Override
    public void close() throws Exception {
        try {
            if (storageRoot != null) {
                java.nio.file.Files.delete(storageRoot);
                storageRoot = null;
            }
        } finally {
            // A storage cleanup failure must not leave the isolated database behind.
            if (created) {
                try (var connection = DriverManager.getConnection(adminUrl, username, password);
                        var statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE \"" + database + "\" WITH (FORCE)");
                    created = false;
                }
            }
        }
    }
}
