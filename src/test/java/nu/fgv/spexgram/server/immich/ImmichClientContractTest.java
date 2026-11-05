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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoints and permissions {@link ImmichClient} and {@link ImmichKeys} rely on exist in the OpenAPI document of
 * the pinned Immich version. Replace {@code immich/immich-openapi-specs.json} when upgrading Immich, and this test says
 * what changed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ImmichClientContractTest {

    private static final JsonNode SPEC = read();

    private static JsonNode read() {
        try (InputStream stream = ImmichClientContractTest.class.getResourceAsStream("/immich/immich-openapi-specs.json")) {
            return JsonMapper.builder().build().readTree(Objects.requireNonNull(stream));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "get, /server/ping", "get, /server/config", "post, /auth/admin-sign-up", "post, /auth/login", "post, /auth/logout",
            "get, /system-config", "put, /system-config", "get, /api-keys", "post, /api-keys", "delete, /api-keys/{id}",
            "get, /api-keys/me", "post, /assets", "get, /assets/{id}", "delete, /assets", "post, /search/metadata",
            "post, /search/smart", "post, /download/archive", "post, /assets/jobs", "get, /queues", "get, /server/about",
            "get, /server/storage", "get, /assets/{id}/thumbnail", "get, /assets/{id}/original", "get, /assets/{id}/video/playback"
    })
    void theEndpointExists(final String method, final String path) {
        assertThat(SPEC.path("paths").path(path).has(method)).as("%s %s", method, path).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"API", "MEDIA", "BULK_IMPORT"})
    void theKeysPermissionsExist(final ImmichKeys.Purpose purpose) {
        final Set<String> permissions = new HashSet<>();

        SPEC.path("components").path("schemas").path("Permission").path("enum").forEach(value -> permissions.add(value.asString()));
        assertThat(permissions).containsAll(purpose.permissions());
    }

    @ParameterizedTest
    @CsvSource({"storageTemplate, enabled", "trash, enabled", "newVersionCheck, enabled", "oauth, enabled"})
    void theConfigurationTheBootstrapChangesExists(final String section, final String field) {
        final String schema = SPEC.path("components").path("schemas").path("AdminConfigDto").path("properties").path(section).path("$ref").asString()
                .replace("#/components/schemas/", "");

        assertThat(SPEC.path("components").path("schemas").path(schema).path("properties").has(field)).isTrue();
    }
}
