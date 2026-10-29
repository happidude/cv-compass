package com.cvcompass.backend.identity.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.security.provision")
public class UserProvisioningProperties {
    private String username = "";
    private String hashFile = "";

    @Pattern(regexp = "USER|ADMIN")
    private String role = "USER";

    @AssertTrue(message = "Provision username/hash-file must both be supplied with valid username")
    public boolean isValid() {
        return username != null
                && hashFile != null
                && (username.isEmpty() && hashFile.isEmpty()
                        || username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
                                && !hashFile.isBlank());
    }
}
