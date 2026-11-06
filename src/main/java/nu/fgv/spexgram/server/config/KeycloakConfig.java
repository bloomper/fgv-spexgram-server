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


package nu.fgv.spexgram.server.config;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two Keycloak admin clients. The service client ({@code fgv-spexgram-service}, {@code view-users}) reads the email
 * addresses of editors and reporters for notifications. The admin client ({@code fgv-admin}) is used only by the sample
 * data import, which grants roles.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration(proxyBeanMethods = false)
public class KeycloakConfig {

    public static final String ADMIN = "keycloakAdmin";

    private static Keycloak build(final String url, final String realm, final String clientId, final String clientSecret) {
        return KeycloakBuilder.builder()
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .serverUrl(url)
                .realm(realm)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    @Bean(destroyMethod = "close")
    @Primary
    public Keycloak keycloakService(final SpexgramProperties properties) {
        final SpexgramProperties.Keycloak keycloak = properties.keycloak();

        return build(keycloak.url(), keycloak.realm(), keycloak.serviceClientId(), keycloak.serviceClientSecret());
    }

    @Bean(name = ADMIN, destroyMethod = "close")
    @Qualifier(ADMIN)
    public Keycloak keycloakAdmin(final SpexgramProperties properties) {
        final SpexgramProperties.Keycloak keycloak = properties.keycloak();

        return build(keycloak.url(), keycloak.realm(), keycloak.adminClientId(), keycloak.adminClientSecret());
    }
}
