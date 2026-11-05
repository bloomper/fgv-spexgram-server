/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package nu.fgv.spexgram.server.user;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

/**
 * The caller of the current request with a token: their Keycloak subject, username, display name, email and roles on
 * {@code fgv-spexgram}. Requests without a token have no caller; see {@code Viewer} for what they see.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Caller(String subject, String username, @Nullable String displayName, @Nullable String email,
                     Set<Role> roles) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean isUser() {
        return roles.contains(Role.USER);
    }

    /**
     * Editors see unpublished albums and items and change the archive; admins can do everything editors can.
     */
    public boolean isEditor() {
        return isUser() && (roles.contains(Role.EDITOR) || roles.contains(Role.ADMIN));
    }

    public boolean isAdmin() {
        return isUser() && roles.contains(Role.ADMIN);
    }

    public boolean isSpexipediaService() {
        return roles.contains(Role.SPEXIPEDIA);
    }

    public void requireUser() {
        if (!isUser()) {
            throw new AccessDeniedException("USER required");
        }
    }

    public void requireEditor() {
        if (!isEditor()) {
            throw new AccessDeniedException("EDITOR required");
        }
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new AccessDeniedException("ADMIN required");
        }
    }
}
