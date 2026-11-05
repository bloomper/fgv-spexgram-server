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


package nu.fgv.spexgram.server.report;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * Checks a visitor's Cloudflare Turnstile token with Cloudflare's siteverify, with the secret in
 * {@code API_TURNSTILE_SECRET_KEY}. Locally and in tests that is Cloudflare's documented secret that accepts every token.
 * Without a secret, or when Cloudflare cannot be reached, visitors' reports are refused. A token must also have been
 * issued on the web's host ({@code spexgram.web-url}), so that one solved on another site is not accepted; Cloudflare's
 * test secrets answer with {@code example.com}, so the host is not checked with them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class TurnstileVerifier {

    private final RestClient client;
    private final String verifyUrl;
    private final String secret;
    private final @Nullable String hostname;

    public TurnstileVerifier(final RestClient.Builder builder, final SpexgramProperties properties) {
        this.client = builder.clone().build();
        this.verifyUrl = properties.turnstile().verifyUrl();
        this.secret = properties.turnstile().secretKey() == null ? "" : properties.turnstile().secretKey().strip();
        this.hostname = isTestSecret(secret) ? null : hostOf(properties.webUrl());
    }

    /**
     * Cloudflare's documented test secrets, which pass or fail every token and answer with {@code example.com}.
     */
    static boolean isTestSecret(final String secret) {
        return secret.matches("[123]x0{31}AA");
    }

    static @Nullable String hostOf(final @Nullable String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            return URI.create(url.strip()).getHost();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether siteverify's answer accepts the token, for this site when the host is known.
     */
    static boolean accepts(final @Nullable JsonNode response, final @Nullable String hostname) {
        if (response == null || !response.path("success").asBoolean(false)) {
            return false;
        }
        return hostname == null || hostname.equalsIgnoreCase(response.path("hostname").asString(""));
    }

    public boolean verify(final String token) {
        if (secret.isEmpty() || !StringUtils.hasText(token)) {
            return false;
        }

        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        form.add("secret", secret);
        form.add("response", token);
        try {
            final JsonNode response = client.post().uri(verifyUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            return accepts(response, hostname);
        } catch (final RestClientException e) {
            log.warn("Could not reach Cloudflare Turnstile: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
