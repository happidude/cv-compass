package com.cvcompass.backend.identity.config;

import com.cvcompass.backend.identity.service.UserProvisioningService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserProvisioningProperties.class)
public class UserProvisioning implements ApplicationRunner {
    private final UserProvisioningProperties properties;
    private final UserProvisioningService service;

    public UserProvisioning(
            UserProvisioningProperties properties, UserProvisioningService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        service.provision(properties.getUsername(), properties.getHashFile(), properties.getRole());
    }
}
