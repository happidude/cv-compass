package com.cvcompass.backend.identity.dto;

import java.util.List;

public record CurrentUser(String username, List<String> roles) {}
