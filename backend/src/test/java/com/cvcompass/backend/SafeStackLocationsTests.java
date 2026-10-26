package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cvcompass.backend.web.SafeStackLocations;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SafeStackLocationsTests {
    private static StackTraceElement frame(String method, int line) {
        return new StackTraceElement(
                "com.cvcompass.backend.TestService", method, "TestService.java", line);
    }

    @Test
    void applicationFramesOnlyWithoutMessagesCausesOrSuppressedText() {
        var failure =
                new RuntimeException("PRIVATE_TOKEN email@example.test") {
                    @Override
                    public String toString() {
                        throw new AssertionError("must never format Throwable");
                    }
                };
        var cause = new IllegalStateException("PRIVATE_CAUSE");
        cause.setStackTrace(new StackTraceElement[] {frame("persist", 22)});
        failure.initCause(cause);
        failure.addSuppressed(new RuntimeException("PRIVATE_SUPPRESSED"));
        failure.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement("org.jdbc.Driver", "bind", "Driver.java", 99),
                    frame("save", 11)
                });
        assertThat(SafeStackLocations.capture(failure).value())
                .isEqualTo(
                        "com.cvcompass.backend.TestService.save(TestService.java:11);com.cvcompass.backend.TestService.persist(TestService.java:22)");
    }

    @Test
    void framesLengthCauseDepthAndCyclesAreBounded() {
        var failure = new RuntimeException("PRIVATE");
        var frames = new StackTraceElement[100];
        Arrays.fill(frames, frame("save", 42));
        failure.setStackTrace(frames);
        String bounded = SafeStackLocations.capture(failure).value();
        assertThat(bounded.split(";")).hasSize(SafeStackLocations.MAX_FRAMES);
        assertThat(bounded.length()).isLessThanOrEqualTo(SafeStackLocations.MAX_LENGTH);
        var first = new RuntimeException();
        first.setStackTrace(new StackTraceElement[] {frame("first", 1)});
        var last = first;
        for (int i = 0; i < 8; i++) {
            var next = new RuntimeException("PRIVATE");
            next.setStackTrace(new StackTraceElement[] {frame("cause" + i, i)});
            last.initCause(next);
            last = next;
        }
        last.initCause(first);
        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> {
                    String result = SafeStackLocations.capture(first).value();
                    assertThat(result.split(";")).hasSize(SafeStackLocations.MAX_CAUSES);
                    assertThat(result).doesNotContain("cause2", "PRIVATE");
                });
        var a = new RuntimeException();
        var b = new RuntimeException();
        a.initCause(b);
        b.initCause(a);
        a.setStackTrace(new StackTraceElement[] {frame("cycleA", 1)});
        b.setStackTrace(new StackTraceElement[] {frame("cycleB", 2)});
        assertThat(SafeStackLocations.capture(a).value().split(";")).hasSize(2);
    }

    @Test
    void untrustedFilePathsAndOverlongMetadataCannotEnterLocations() {
        var failure = new RuntimeException("PRIVATE");
        failure.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement(
                            "com.cvcompass.backend.Service", "run", "C:/private/TOKEN.java", 7),
                    new StackTraceElement(
                            "com.cvcompass.backend." + "x".repeat(5000), "run", "Source.java", 8)
                });
        assertThat(SafeStackLocations.capture(failure).value())
                .isEqualTo("com.cvcompass.backend.Service.run(UnknownSource:7)");
        assertThat(SafeStackLocations.capture(null).value()).isEmpty();
    }

    @Test
    void characterBudgetAndUnavailableCauseRemainSafe() {
        var failure =
                new RuntimeException("PRIVATE") {
                    @Override
                    public synchronized Throwable getCause() {
                        throw new IllegalStateException("PRIVATE_CAUSE");
                    }
                };
        var large =
                new StackTraceElement(
                        "com.cvcompass.backend." + "x".repeat(160),
                        "y".repeat(90),
                        "z".repeat(90) + ".java",
                        42);
        var frames = new StackTraceElement[100];
        Arrays.fill(frames, large);
        failure.setStackTrace(frames);
        String bounded = SafeStackLocations.capture(failure).value();
        assertThat(bounded.length()).isLessThanOrEqualTo(SafeStackLocations.MAX_LENGTH);
        assertThat(bounded.split(";").length).isLessThan(SafeStackLocations.MAX_FRAMES);
        failure.setStackTrace(new StackTraceElement[] {frame("save", 1)});
        assertThat(SafeStackLocations.capture(failure).value())
                .contains("save")
                .doesNotContain("PRIVATE");
    }
}
