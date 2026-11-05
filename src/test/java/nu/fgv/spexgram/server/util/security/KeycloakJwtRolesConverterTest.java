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

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak's realm and client roles as authorities.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class KeycloakJwtRolesConverterTest {

    private final KeycloakJwtRolesConverter converter = new KeycloakJwtRolesConverter();

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("s-1");
    }

    @Test
    void realmAndClientRolesBecomeAuthorities() {
        final Map<String, Object> resources = new HashMap<>();

        resources.put("fgv-spexgram", Map.of("roles", List.of("USER", "EDITOR")));
        resources.put("account", Map.of("roles", List.of()));
        resources.put("broken", null);

        final Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("offline_access"))).claim("resource_access", resources).build();

        assertThat(converter.convert(token)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_realm_offline_access", "ROLE_fgv-spexgram_USER", "ROLE_fgv-spexgram_EDITOR");
    }

    @Test
    void aTokenWithoutRolesHasNoAuthorities() {
        assertThat(converter.convert(jwt().claim("realm_access", Map.of()).build())).isEmpty();
        assertThat(converter.convert(jwt().claim("realm_access", Map.of("roles", List.of())).claim("resource_access", Map.of()).build())).isEmpty();
    }
}
