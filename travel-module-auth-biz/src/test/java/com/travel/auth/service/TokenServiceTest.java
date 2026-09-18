package com.travel.auth.service;

import com.travel.auth.infrastructure.AuthSessionRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {
    @Test void expiredOrRevokedSessionIsRejected() {
        AuthSessionRepository repo = mock(AuthSessionRepository.class);
        TokenService service = new TokenService(repo);
        when(repo.findUserId(anyString())).thenReturn(Optional.empty());
        assertNull(service.verify("expired-token"));
    }
    @Test void validSessionReturnsOwner() {
        AuthSessionRepository repo = mock(AuthSessionRepository.class);
        TokenService service = new TokenService(repo);
        when(repo.findUserId(anyString())).thenReturn(Optional.of("42"));
        assertEquals("42", service.verify("valid-token"));
    }
}
