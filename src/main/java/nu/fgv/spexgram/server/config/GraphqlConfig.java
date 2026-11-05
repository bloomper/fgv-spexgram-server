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

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import nu.fgv.spexgram.server.util.graphql.CustomScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.Map;


/**
 * Scalars, query limits, and the client's address in the GraphQL context.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
public class GraphqlConfig {

    /**
     * The GraphQL context's key for the visitor's address: the web passes it in {@value #CLIENT_IP_HEADER} (from
     * Cloudflare's or the proxy's header), else the caller's own address is used. Only used to limit visitors' reports.
     */
    public static final String CLIENT_IP = "clientIp";
    public static final String CLIENT_IP_HEADER = "X-Client-IP";

    @Bean
    public WebGraphQlInterceptor clientIpInterceptor() {
        return (request, chain) -> {
            final String header = request.getHeaders().getFirst(CLIENT_IP_HEADER);
            final InetSocketAddress remote = request.getRemoteAddress();
            final String ip = StringUtils.hasText(header) ? header.strip()
                    : remote == null ? null : remote.getHostString();

            if (ip != null) {
                request.configureExecutionInput((input, builder) -> builder.graphQLContext(Map.of(CLIENT_IP, ip)).build());
            }
            return chain.next(request);
        };
    }

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(CustomScalars.INSTANT)
                .scalar(CustomScalars.VOID);
    }

    @Bean
    public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(10);
    }

    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(500);
    }

}
