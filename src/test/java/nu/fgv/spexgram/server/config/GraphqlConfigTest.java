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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.support.DefaultGraphQlRequest;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static nu.fgv.spexgram.server.config.GraphqlConfig.CLIENT_IP;
import static nu.fgv.spexgram.server.config.GraphqlConfig.CLIENT_IP_HEADER;
import static nu.fgv.spexgram.server.config.GraphqlConfig.CLIENT_IP_SECRET_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The visitor's address in the GraphQL context: named by the web only with the shared secret.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GraphqlConfigTest {

    private static final InetSocketAddress CALLER = new InetSocketAddress("198.51.100.9", 443);

    private static @Nullable Object clientIp(final @Nullable String secret, final Map<String, String> headers) {
        final SpexgramProperties properties = new SpexgramProperties(null, null, null,
                new SpexgramProperties.Security(List.of(), secret), null, null, null, null, null, null);
        final WebGraphQlInterceptor interceptor = new GraphqlConfig().clientIpInterceptor(properties);
        final HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        final WebGraphQlRequest request = new WebGraphQlRequest(URI.create("http://localhost/api/graphql"), httpHeaders,
                null, CALLER, Map.of(), new DefaultGraphQlRequest("{ __typename }"), "1", null);
        final AtomicReference<WebGraphQlRequest> seen = new AtomicReference<>();

        interceptor.intercept(request, next -> {
            seen.set(next);
            return Mono.empty();
        }).block();
        return seen.get().toExecutionInput().getGraphQLContext().get(CLIENT_IP);
    }

    @Test
    void usesTheAddressTheWebNamesWithTheSecret() {
        assertThat(clientIp("s3cret", Map.of(CLIENT_IP_HEADER, " 203.0.113.7 ", CLIENT_IP_SECRET_HEADER, "s3cret")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void ignoresANamedAddressWithoutTheRightSecret() {
        assertThat(clientIp("s3cret", Map.of(CLIENT_IP_HEADER, "203.0.113.7"))).isEqualTo("198.51.100.9");
        assertThat(clientIp("s3cret", Map.of(CLIENT_IP_HEADER, "203.0.113.7", CLIENT_IP_SECRET_HEADER, "guess")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    void ignoresANamedAddressWhenNoSecretIsConfigured() {
        assertThat(clientIp(null, Map.of(CLIENT_IP_HEADER, "203.0.113.7", CLIENT_IP_SECRET_HEADER, ""))).isEqualTo("198.51.100.9");
        assertThat(clientIp("", Map.of(CLIENT_IP_HEADER, "203.0.113.7", CLIENT_IP_SECRET_HEADER, ""))).isEqualTo("198.51.100.9");
    }
}
