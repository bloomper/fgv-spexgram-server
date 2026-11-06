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

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * The GraphQL client for Spexregister: client credentials of {@code fgv-spexgram-service}, which holds only the
 * {@code MEDIA} role, with 3-second timeouts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
public class SpexregisterClientConfig {

    public static final String REGISTRATION_ID = "fgv-spexgram-service";

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public OAuth2AuthorizedClientManager spexregisterAuthorizedClientManager(final ClientRegistrationRepository clientRegistrationRepository,
                                                                             final OAuth2AuthorizedClientService authorizedClientService) {
        final AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);

        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /**
     * Spexregister's API with {@code fgv-spexgram-service}'s token.
     */
    @Bean
    public RestClient spexregisterRestClient(final SpexgramProperties properties,
                                             final OAuth2AuthorizedClientManager spexregisterAuthorizedClientManager) {
        return RestClient.builder()
                                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                        .withConnectTimeout(TIMEOUT)
                        .withReadTimeout(TIMEOUT)))
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(spexregisterAuthorizedClientManager))
                .defaultRequest(request -> request.attributes(clientRegistrationId(REGISTRATION_ID)))
                .build();
    }

    @Bean
    public HttpSyncGraphQlClient spexregisterGraphQlClient(final SpexgramProperties properties,
                                                           final RestClient spexregisterRestClient) {
        return HttpSyncGraphQlClient.builder(spexregisterRestClient).url(properties.spexregister().graphqlUrl()).build();
    }

}
