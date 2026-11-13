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
 * The clients for Spexipedia: GraphQL with the client credentials of {@code fgv-spexgram-service}, which holds
 * {@code SYNC} on {@code fgv-spexipedia} for its change feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
public class SpexipediaClientConfig {

    public static final String REGISTRATION_ID = "fgv-spexgram-service";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // A page of the change feed is up to 500 records
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public OAuth2AuthorizedClientManager serviceAuthorizedClientManager(final ClientRegistrationRepository clientRegistrationRepository,
                                                                        final OAuth2AuthorizedClientService authorizedClientService) {
        final AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);

        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    public HttpSyncGraphQlClient spexipediaGraphQlClient(final SpexgramProperties properties,
                                                         final OAuth2AuthorizedClientManager serviceAuthorizedClientManager) {
        final RestClient restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(serviceAuthorizedClientManager))
                .defaultRequest(request -> request.attributes(clientRegistrationId(REGISTRATION_ID)))
                .build();

        return HttpSyncGraphQlClient.builder(restClient).url(properties.spexipedia().graphqlUrl()).build();
    }
}
