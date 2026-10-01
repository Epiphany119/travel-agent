package com.travel.module.user.biz.domain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves platform roles from trusted server configuration.
 * Every authenticated account is a USER; ADMIN also inherits moderator capabilities.
 */
@Component
public class PlatformRoleAuthorizationService {
    private final Set<String> moderatorIds;
    private final Set<String> adminIds;

    public PlatformRoleAuthorizationService(
            @Value("${travel.security.moderator-user-ids:}") String moderatorIds,
            @Value("${travel.security.admin-user-ids:}") String adminIds) {
        this.moderatorIds = parseIds(moderatorIds);
        this.adminIds = parseIds(adminIds);
    }

    public Set<PlatformRole> rolesFor(String userId) {
        if (userId == null || userId.isBlank()) return Set.of();
        EnumSet<PlatformRole> roles = EnumSet.of(PlatformRole.USER);
        if (adminIds.contains(userId)) {
            roles.add(PlatformRole.ADMIN);
            roles.add(PlatformRole.MODERATOR);
        } else if (moderatorIds.contains(userId)) {
            roles.add(PlatformRole.MODERATOR);
        }
        return Collections.unmodifiableSet(roles);
    }

    public void requireRole(String userId, PlatformRole requiredRole) {
        if (!rolesFor(userId).contains(requiredRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient role for this operation");
        }
    }

    private Set<String> parseIds(String configuredIds) {
        if (configuredIds == null || configuredIds.isBlank()) return Set.of();
        return Arrays.stream(configuredIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
