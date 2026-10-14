package com.cvcompass.backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import java.sql.DriverManager;
import java.util.UUID;

/** One instance belongs to one integration test class; no shared mutable schema state. */
final class IsolatedPostgresSchema implements AutoCloseable {
    private final String schema = "cv_persistence_test_" + UUID.randomUUID().toString().replace("-", "");
    private final String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/cv_compass");
    private final String username = System.getenv().getOrDefault("TEST_DB_USERNAME", "cvcompass");
    private final String password = System.getenv("TEST_DB_PASSWORD");
    private boolean created;

    String schemaName() {
        return schema;
    }

    void configure(DynamicPropertyRegistry registry) throws Exception {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set TEST_DB_PASSWORD for isolated PostgreSQL integration tests");
        }
        if (!created) {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                created = true;
            }
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.hikari.schema", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    @Override
    public void close() throws Exception {
        if (created) {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
                created = false;
            }
        }
    }
}
