package com.cvcompass.backend.identity.dto;

/** Internal persistence read model; never returned by an HTTP endpoint. */
public record UserAccount(String username, String passwordHash, String role, boolean enabled) {
    @Override
    public String toString() {
        return "UserAccount[redacted]";
    }
}
