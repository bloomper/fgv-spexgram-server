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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Checks a visitor's Cloudflare Turnstile token with Cloudflare's siteverify, with the secret in
 * {@code API_TURNSTILE_SECRET_KEY}. Locally and in tests that is Cloudflare's documented secret that accepts every token.
 * Without a secret, or when Cloudflare cannot be reached, visitors' reports are refused.
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

    public TurnstileVerifier(final RestClient.Builder builder, final SpexgramProperties properties) {
        this.client = builder.clone().build();
        this.verifyUrl = properties.turnstile().verifyUrl();
        this.secret = properties.turnstile().secretKey() == null ? "" : properties.turnstile().secretKey().strip();
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

            return response != null && response.path("success").asBoolean(false);
        } catch (final RestClientException e) {
            log.warn("Could not reach Cloudflare Turnstile: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
