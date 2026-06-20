package com.iptv.saas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserControllerTests {
    private final UserRepository users = mock(UserRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final AdminUserController controller = new AdminUserController(users, new ObjectMapper(), tokens);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteAnonymizesUserAndRevokesTokens() {
        UserEntity actor = user(1L, Enums.UserRole.ADMIN, "admin@example.test");
        UserEntity target = user(2L, Enums.UserRole.USER, "client@example.test");
        target.name = "Client";
        target.emailVerified = true;
        target.twoFactorEnabled = true;
        target.emailOtp = "123456";
        target.resetOtp = "654321";
        target.twoFactorCode = "111111";
        target.allowedCategories = "[\"live\"]";
        authenticate(actor);
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.save(target)).thenReturn(target);

        controller.delete(2L);

        verify(tokens).revokeAll(target);
        verify(users).save(target);
        assertFalse(target.active);
        assertEquals("Utilisateur supprime", target.name);
        assertEquals("deleted-user-2@deleted.local", target.email);
        assertEquals("[]", target.allowedCategories);
        assertFalse(target.emailVerified);
        assertFalse(target.twoFactorEnabled);
        assertNull(target.emailOtp);
        assertNull(target.resetOtp);
        assertNull(target.twoFactorCode);
    }

    @Test
    void deleteRefusesCurrentUser() {
        UserEntity actor = user(1L, Enums.UserRole.ADMIN, "admin@example.test");
        authenticate(actor);

        assertThrows(ApiException.class, () -> controller.delete(1L));

        verify(tokens, never()).revokeAll(actor);
    }

    @Test
    void deleteRefusesLastActiveSuperAdmin() {
        UserEntity actor = user(1L, Enums.UserRole.ADMIN, "admin@example.test");
        UserEntity target = user(2L, Enums.UserRole.SUPER_ADMIN, "owner@example.test");
        authenticate(actor);
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.findAll()).thenReturn(List.of(target));

        assertThrows(ApiException.class, () -> controller.delete(2L));

        verify(tokens, never()).revokeAll(target);
    }

    private void authenticate(UserEntity user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of())
        );
    }

    private UserEntity user(Long id, Enums.UserRole role, String email) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.role = role;
        user.email = email;
        user.name = email;
        user.active = true;
        return user;
    }
}
