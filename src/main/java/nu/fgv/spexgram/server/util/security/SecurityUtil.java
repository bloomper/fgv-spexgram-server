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

package nu.fgv.spexgram.server.util.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Reads the current access token.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class SecurityUtil {

    public static final String SYSTEM = "system";
    public static final String CLIENT_ID = "fgv-spexgram";

    private SecurityUtil() {
    }

    public static Optional<Authentication> currentAuthentication() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated);
    }

    public static Optional<Jwt> currentJwt() {
        return currentAuthentication()
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast);
    }

    /**
     * The Keycloak subject of the caller, or {@value #SYSTEM} when there is none. Never an email address.
     */
    public static String currentSubjectOrSystem() {
        return currentJwt()
                .map(Jwt::getSubject)
                .filter(StringUtils::hasText)
                .orElse(SYSTEM);
    }
}
