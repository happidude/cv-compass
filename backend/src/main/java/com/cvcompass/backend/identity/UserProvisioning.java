package com.cvcompass.backend.identity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserProvisioning.Properties.class)
public class UserProvisioning implements ApplicationRunner {
    @Getter
    @Setter
    @Validated
    @ConfigurationProperties("cv.security.provision")
    public static class Properties {
        private String username = "";
        private String hashFile = "";

        @Pattern(regexp = "USER|ADMIN")
        private String role = "USER";

        @AssertTrue(
                message = "Provision username/hash-file must both be supplied with valid username")
        public boolean isValid() {
            return username != null
                    && hashFile != null
                    && (username.isEmpty() && hashFile.isEmpty()
                            || username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
                                    && !hashFile.isBlank());
        }
    }

    private final Properties properties;
    private final JdbcTemplate jdbc;

    public UserProvisioning(Properties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.getUsername().isEmpty()) return;
        try {
            var path = Path.of(properties.getHashFile());
            if (!path.isAbsolute() || Files.size(path) > 100) throw new IllegalArgumentException();
            String hash = Files.readString(path).strip();
            if (!hash.matches("\\$2[aby]\\$(1[0-6]|0[4-9])\\$[./A-Za-z0-9]{53}"))
                throw new IllegalArgumentException();
            jdbc.update(
                    "INSERT INTO \"CV_USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES (?,?,?) ON CONFLICT (\"USERNAME\") DO NOTHING",
                    properties.getUsername(),
                    hash,
                    properties.getRole());
        } catch (Exception ex) {
            throw new IllegalStateException("USER_PROVISIONING_FAILED");
        }
    }
}
