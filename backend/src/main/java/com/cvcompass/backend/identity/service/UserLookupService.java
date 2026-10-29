package com.cvcompass.backend.identity.service;

import com.cvcompass.backend.identity.repository.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserLookupService {
    private final UserRepository repository;

    public UserLookupService(UserRepository repository) {
        this.repository = repository;
    }

    public UserDetails load(String username) {
        var account =
                repository
                        .find(username)
                        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .roles(account.role())
                .disabled(!account.enabled())
                .build();
    }
}
