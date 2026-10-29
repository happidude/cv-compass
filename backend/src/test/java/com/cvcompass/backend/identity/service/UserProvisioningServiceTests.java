package com.cvcompass.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cvcompass.backend.identity.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserProvisioningServiceTests {
    @TempDir Path directory;
    private final UserRepository repository = mock(UserRepository.class);
    private final UserProvisioningService service = new UserProvisioningService(repository);

    @Test
    void disabledProvisioningDoesNotReadFileOrWriteUser() {
        service.provision("", "", "USER");
        verifyNoInteractions(repository);
    }

    @Test
    void validHashFileIsStrippedAndSentToIdempotentRepository() throws Exception {
        String hash = new BCryptPasswordEncoder(4).encode("synthetic-only");
        var file = directory.resolve("synthetic.hash");
        Files.writeString(file, hash + "\n");
        service.provision("synthetic", file.toString(), "ADMIN");
        verify(repository).provision("synthetic", hash, "ADMIN");
    }

    @Test
    void invalidHashAndRelativeOrMissingFilesFailWithoutLeakingCause() throws Exception {
        var invalid = directory.resolve("invalid.hash");
        Files.writeString(invalid, "synthetic-invalid");
        for (String path :
                new String[] {
                    invalid.toString(),
                    "relative.hash",
                    directory.resolve("missing.hash").toString()
                }) {
            assertThatThrownBy(() -> service.provision("synthetic", path, "USER"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("USER_PROVISIONING_FAILED")
                    .hasNoCause();
        }
        verifyNoInteractions(repository);
    }
}
