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

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API sets Immich up with three API keys, each with only the permissions it needs, kept as files for the media
 * server and for bulk imports (section 8 of the spec).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ImmichBootstrapIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ImmichKeys keys;
    @Autowired
    private ImmichBootstrap bootstrap;

    @Test
    void theKeysAreKeptAsFilesWithTheirPermissions() throws Exception {
        for (final ImmichKeys.Purpose purpose : ImmichKeys.Purpose.values()) {
            final String folder = purpose == ImmichKeys.Purpose.MEDIA ? "immich-media-key" : "immich-keys";

            assertThat(DATA.resolve(folder).resolve(purpose.fileName())).isNotEmptyFile();
            assertThat(immichClient.apiKey(keys.read(purpose).orElseThrow()))
                    .hasValueSatisfying(key -> assertThat(key.permissions()).containsExactlyInAnyOrderElementsOf(purpose.permissions()));
        }
        assertThat(keys.isReady()).isTrue();
        // The media server mounts only the media key's folder, which holds nothing else
        assertThat(DATA.resolve("immich-keys/media.key")).doesNotExist();
        try (var files = Files.list(DATA.resolve("immich-media-key"))) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("media.key");
        }
    }

    @Test
    void theMediaKeyCannotDeleteAndTheBulkImportKeyCannotRead() throws Exception {
        final String media = Files.readString(DATA.resolve("immich-media-key/media.key"));
        final String bulk = Files.readString(DATA.resolve("immich-keys/bulk-import.key"));
        final RestClient immich = RestClient.builder()
                .baseUrl(immichUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build();
        final int delete = immich.method(HttpMethod.DELETE).uri("/api/assets")
                .header(ImmichClient.API_KEY_HEADER, media)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"ids\": [\"" + UUID.randomUUID() + "\"]}")
                .exchange((request, response) -> response.getStatusCode().value());
        final int search = immich.post().uri("/api/search/metadata")
                .header(ImmichClient.API_KEY_HEADER, bulk)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(delete).isEqualTo(403);
        assertThat(search).isEqualTo(403);
    }

    @Test
    void settingUpAgainKeepsValidKeys() throws Exception {
        final String before = keys.read(ImmichKeys.Purpose.API).orElseThrow();

        bootstrap.setUp();

        assertThat(keys.read(ImmichKeys.Purpose.API)).contains(before);
    }
}
