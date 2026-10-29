package com.cvcompass.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.identity.dto.UserAccount;
import com.cvcompass.backend.identity.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class UserLookupServiceTests {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserLookupService service = new UserLookupService(repository);

    @Test
    void lookupRetainsRolePasswordAndDisabledStatus() {
        when(repository.find("synthetic"))
                .thenReturn(
                        Optional.of(
                                new UserAccount("synthetic", "synthetic-hash", "ADMIN", false)));
        var user = service.load("synthetic");
        assertThat(user.getUsername()).isEqualTo("synthetic");
        assertThat(user.getPassword()).isEqualTo("synthetic-hash");
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void absentUserRetainsSanitizedAuthenticationFailure() {
        when(repository.find("absent")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.load("absent"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Invalid credentials");
    }
}
