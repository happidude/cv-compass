package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class LoggingJsonTests {
    @Test
    void configuredJsonHasCommonFieldsAndStructuredContext() throws Exception {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        context.start();
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        try {
            encoder.start();
            var event = new LoggingEvent();
            event.setTimeStamp(System.currentTimeMillis());
            event.setLevel(ch.qos.logback.classic.Level.INFO);
            event.setLoggerName("com.cvcompass.events");
            event.setThreadName("synthetic-test");
            event.setMessage("DOCUMENT_STORED");
            String locations =
                    com.cvcompass.backend.web.SafeStackLocations.capture(
                                    new RuntimeException("PRIVATE"))
                            .value();
            event.setMDCPropertyMap(
                    Map.of(
                            "event",
                            "DOCUMENT_STORED",
                            "errorCode",
                            "NONE",
                            "documentId",
                            "42",
                            "correlationId",
                            "synthetic-request",
                            "applicationStack",
                            locations));
            var json = JsonMapper.builder().build().readTree(encoder.encode(event));
            assertThat(json.path("timestamp").asString()).contains("T");
            assertThat(json.has("@timestamp")).isFalse();
            assertThat(json.path("service").asString()).isEqualTo("cv-compass-backend");
            assertThat(json.path("level").asString()).isEqualTo("INFO");
            assertThat(json.path("event").asString()).isEqualTo("DOCUMENT_STORED");
            assertThat(json.path("documentId").asString()).isEqualTo("42");
            assertThat(json.path("errorCode").asString()).isEqualTo("NONE");
            assertThat(json.path("applicationStack").asString())
                    .contains("com.cvcompass.backend.LoggingJsonTests")
                    .doesNotContain("PRIVATE");
        } finally {
            encoder.stop();
            context.stop();
        }
    }
}
