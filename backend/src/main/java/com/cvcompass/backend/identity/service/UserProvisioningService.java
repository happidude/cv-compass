package com.cvcompass.backend.identity.service;

import com.cvcompass.backend.identity.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class UserProvisioningService {
    private final UserRepository repository;

    public UserProvisioningService(UserRepository repository) {
        this.repository = repository;
    }

    public void provision(String username, String hashFile, String role) {
        if (username.isEmpty()) return;
        try {
            var path = Path.of(hashFile);
            if (!path.isAbsolute() || Files.size(path) > 100) throw new IllegalArgumentException();
            String hash = Files.readString(path).strip();
            if (!hash.matches("\\$2[aby]\\$(1[0-6]|0[4-9])\\$[./A-Za-z0-9]{53}"))
                throw new IllegalArgumentException();
            repository.provision(username, hash, role);
        } catch (Exception ex) {
            throw new IllegalStateException("USER_PROVISIONING_FAILED");
        }
    }
}
