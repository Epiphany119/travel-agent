package com.travel.module.user.biz.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class PlatformRoleAuthorizationServiceTest {
    @Test
    void resolvesConfiguredRolesAndAdminInheritance() {
        PlatformRoleAuthorizationService roles =
                new PlatformRoleAuthorizationService(" mod-1, ", "admin-1");
        assertEquals(java.util.Set.of(PlatformRole.USER), roles.rolesFor("user-1"));
        assertTrue(roles.rolesFor("mod-1").contains(PlatformRole.MODERATOR));
        assertFalse(roles.rolesFor("mod-1").contains(PlatformRole.ADMIN));
        assertTrue(roles.rolesFor("admin-1").contains(PlatformRole.ADMIN));
        assertTrue(roles.rolesFor("admin-1").contains(PlatformRole.MODERATOR));
        assertTrue(roles.rolesFor(null).isEmpty());
    }

    @Test
    void deniesRolesNotGrantedByTrustedConfiguration() {
        PlatformRoleAuthorizationService roles = new PlatformRoleAuthorizationService("", "");
        assertThrows(ResponseStatusException.class,
                () -> roles.requireRole("user-1", PlatformRole.MODERATOR));
    }
}