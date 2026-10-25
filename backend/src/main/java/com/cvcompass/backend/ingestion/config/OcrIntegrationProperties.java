package com.cvcompass.backend.ingestion.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Operator-owned enrollment and immutable deployment registry; never HTTP input. */
@Getter @Setter @Validated
@ConfigurationProperties("cv.ocr.integration")
public class OcrIntegrationProperties {
    private boolean enabled;
    private String namespace;
    private Path syntheticStorageRoot;
    @Min(1) @Max(16384) private int controlBodyBytes = 16384;
    @NotNull private Duration reaperDelay = Duration.ofSeconds(15);
    @NotNull private Duration downloadTimeout = Duration.ofSeconds(60);
    @NotNull private Map<String, @Valid Worker> workers = new HashMap<>();
    @NotNull private Map<String, @Valid Profile> profiles = new HashMap<>();
    @NotNull private List<@Valid Fixture> fixtures = new ArrayList<>();

    @Getter @Setter
    public static class Worker {
        @NotEmpty private Set<@NotNull @Pattern(regexp="[0-9a-f]{64}") String> credentialSha256 = new HashSet<>();
        @NotEmpty private Set<@NotNull @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") String> profiles = new HashSet<>();
    }
    @Getter @Setter
    public static class Profile {
        /** Existing profiles remain v1 until an operator explicitly pins a v2 profile. */
        @NotNull @Pattern(regexp="cv-compass\\.ocr/(1|2)\\.0") private String resultVersion = "cv-compass.ocr/1.0";
        @NotNull @Pattern(regexp="[0-9a-f]{40}") private String workerCommit;
        @NotNull @Pattern(regexp="sha256:[0-9a-f]{64}") private String imageId;
        @NotNull @Pattern(regexp="[0-9a-f]{64}") private String configFingerprint;
        /** Exact M6 engines object, including pinned model hashes, supplied by deployment evidence. */
        @NotBlank private String enginesJson;
    }
    @Getter @Setter
    public static class Fixture {
        @NotNull @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") private String fixtureId;
        @NotNull @Pattern(regexp="[0-9a-f]{64}") private String sha256;
        @Min(1) @Max(10485760) private long sizeBytes;
        @NotNull @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") private String profileRevision;
    }
    @AssertTrue(message="Invalid OCR deployment registry or timing")
    public boolean isRegistryValid() {
        if (reaperDelay == null || downloadTimeout == null || reaperDelay.toMillis() < 1
                || downloadTimeout.toMillis() < 1 || reaperDelay.compareTo(Duration.ofDays(1)) > 0
                || downloadTimeout.compareTo(Duration.ofDays(1)) > 0) return false;
        if (!enabled) return true;
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || syntheticStorageRoot != null && !syntheticStorageRoot.isAbsolute()
                || workers.isEmpty() || profiles.isEmpty()) return false;
        Set<String> hashes = new HashSet<>(), credentials = new HashSet<>();
        for (var entry : workers.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                    || !profiles.keySet().containsAll(entry.getValue().profiles)) return false;
            for (String hash : entry.getValue().credentialSha256) if (!credentials.add(hash)) return false;
        }
        for (var entry : profiles.entrySet()) if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) return false;
        for (Fixture fixture : fixtures) if (!hashes.add(fixture.sha256) || !profiles.containsKey(fixture.profileRevision)) return false;
        return true;
    }
}
