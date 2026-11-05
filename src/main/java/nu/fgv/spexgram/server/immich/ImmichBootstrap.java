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


package nu.fgv.spexgram.server.immich;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;

/**
 * Sets Immich up for Spexgram when the API starts (section 8 of the spec): creates its admin, Spexgram's system user,
 * with the password from {@code API_IMMICH_ADMIN_PASSWORD}; turns the storage template, face recognition, Immich's own
 * trash and its version check off and picks the multilingual CLIP model; and makes sure of the three API keys
 * ({@link ImmichKeys.Purpose}), creating any that are missing, invalid or have other permissions. Immich's OAuth login
 * is never configured. While Immich is down the API keeps trying every minute.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ImmichBootstrap {

    static final String SYSTEM_USER_NAME = "Spexgram";
    private static final Duration WAIT_INTERVAL = Duration.ofSeconds(3);

    private final ImmichClient client;
    private final ImmichKeys keys;
    private final SpexgramProperties.Immich properties;

    public ImmichBootstrap(final ImmichClient client, final ImmichKeys keys, final SpexgramProperties properties) {
        this.client = client;
        this.keys = keys;
        this.properties = properties.immich();
    }

    private static void set(final JsonNode config, final String section, final String field, final Object value) {
        JsonNode node = config;

        for (final String part : section.split("\\.")) {
            node = node.path(part);
        }
        if (node instanceof final ObjectNode object) {
            switch (value) {
                case final Boolean b -> object.put(field, b);
                case final String s -> object.put(field, s);
                default -> throw new IllegalArgumentException(String.valueOf(value));
            }
        } else {
            throw new IllegalStateException("Immich's configuration has no " + section);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady() {
        try {
            awaitAndSetUp(properties.startupWait());
        } catch (final ImmichUnavailableException e) {
            log.warn("Immich is not available yet; trying again every minute: {}", e.getMostSpecificCause().getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void retry() {
        if (keys.isReady()) {
            return;
        }
        try {
            setUp();
        } catch (final ImmichUnavailableException e) {
            log.debug("Immich is still not available", e);
        }
    }

    /**
     * Waits for Immich to answer, then sets it up.
     *
     * @throws ImmichUnavailableException when Immich did not answer in time
     */
    public void awaitAndSetUp(final Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);

        while (!client.ping()) {
            if (Instant.now().isAfter(deadline)) {
                throw new ImmichUnavailableException(new IllegalStateException("Immich did not answer at " + properties.url()));
            }
            log.info("Waiting for Immich at {}", properties.url());
            try {
                Thread.sleep(WAIT_INTERVAL);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImmichUnavailableException(e);
            }
        }
        setUp();
    }

    /**
     * Sets Immich up, as described above; does nothing that is already done.
     */
    public synchronized void setUp() {
        if (properties.adminPassword() == null || properties.adminPassword().isBlank()) {
            // Never fall back to a known password: Immich's admin can read and delete everything
            throw new IllegalStateException("API_IMMICH_ADMIN_PASSWORD is not set");
        }
        if (!client.isInitialized()) {
            client.signUpAdmin(properties.adminEmail(), SYSTEM_USER_NAME, properties.adminPassword());
            log.info("Created Immich's admin {}", properties.adminEmail());
        }

        final String token = client.login(properties.adminEmail(), properties.adminPassword());

        try {
            configure(token);
            for (final ImmichKeys.Purpose purpose : ImmichKeys.Purpose.values()) {
                ensureKey(token, purpose);
            }
            keys.useApiKey(keys.read(ImmichKeys.Purpose.API).orElseThrow());
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not keep Immich's API keys in " + properties.keysDir(), e);
        } finally {
            client.logout(token);
        }
    }

    private void configure(final String token) {
        final JsonNode current = client.systemConfig(token);
        final JsonNode wanted = current.deepCopy();

        set(wanted, "storageTemplate", "enabled", false);
        set(wanted, "machineLearning.facialRecognition", "enabled", false);
        set(wanted, "machineLearning.clip", "enabled", true);
        set(wanted, "machineLearning.clip", "modelName", properties.clipModel());
        set(wanted, "newVersionCheck", "enabled", false);
        // Spexgram has a trash of its own; what it deletes in Immich is gone
        set(wanted, "trash", "enabled", false);
        set(wanted, "oauth", "enabled", false);
        // Every video gets a browser version, which Immich makes with -map_metadata -1: viewers who may not download
        // originals are played that, never the original with its location and creation time
        set(wanted, "ffmpeg", "transcode", "all");
        if (!wanted.equals(current)) {
            client.updateSystemConfig(token, wanted);
            log.info("Configured Immich: no storage template, face recognition or trash; every video transcoded; search by content with {}", properties.clipModel());
        }
    }

    private void ensureKey(final String token, final ImmichKeys.Purpose purpose) throws IOException {
        final Optional<String> existing = keys.read(purpose);

        if (existing.isPresent() && client.apiKey(existing.get())
                .filter(key -> key.permissions().equals(new HashSet<>(purpose.permissions())))
                .isPresent()) {
            return;
        }
        for (final ImmichClient.ApiKey key : client.apiKeys(token)) {
            if (key.name().equals(purpose.keyName())) {
                client.deleteApiKey(token, key.id());
            }
        }
        keys.write(purpose, client.createApiKey(token, purpose.keyName(), purpose.permissions()));
        log.info("Created Immich's API key {} in {}", purpose.keyName(), properties.keysDir());
    }
}
