package com.cvcompass.backend.identity.controller;

import com.cvcompass.backend.identity.dto.CsrfResponse;
import com.cvcompass.backend.identity.dto.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @GetMapping("/me")
    public CurrentUser me(Authentication actor) {
        return new CurrentUser(
                actor.getName(), actor.getAuthorities().stream().map(Object::toString).toList());
    }
}
