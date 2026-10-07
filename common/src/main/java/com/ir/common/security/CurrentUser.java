package com.ir.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/** The caller as seen by the service layer: Keycloak {@code sub} plus realm roles. */
public record CurrentUser(String id, Set<String> roles) {

    public static CurrentUser fromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user");
        }
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(auth.getName(), roles);
    }

    public boolean has(String role) {
        return roles.contains(role);
    }

    public boolean hasAny(String... candidates) {
        for (String r : candidates) {
            if (roles.contains(r)) {
                return true;
            }
        }
        return false;
    }
}
