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

import com.fasterxml.jackson.annotation.JsonInclude;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Immich's API on the internal network, the endpoints Spexgram uses of the pinned version
 * ({@code src/main/resources/immich/immich-openapi-specs.json}, checked by {@code ImmichClientContractTest}).
 * Written by hand rather than generated: Spexgram uses some twenty endpoints of several hundred, and reads Immich's
 * large system configuration as a tree it changes a few values in.
 *
 * <p>Calls with the API key go through {@link ImmichKeys}; the admin's session is used only by
 * {@link ImmichBootstrap}. Every failure to reach Immich, or an answer it should not give, is an
 * {@link ImmichUnavailableException}; a file Immich refuses is an {@link ImmichRejectedException}.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ImmichClient {

    public static final String API_KEY_HEADER = "x-api-key";
    static final int PAGE = 1000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Immich does not take the JDK client's upgrade to HTTP/2 on plain HTTP well
    private static final ClientHttpRequestFactoryBuilder<?> IMMICH_HTTP = ClientHttpRequestFactoryBuilder.jdk()
            .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));

    private final RestClient rest;
    private final RestClient transfer;
    private final ImmichKeys keys;

    public ImmichClient(final RestClient.Builder builder, final SpexgramProperties properties, final ImmichKeys keys) {
        final String baseUrl = properties.immich().url().replaceAll("/+$", "") + "/api";

        this.rest = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(IMMICH_HTTP.build(HttpClientSettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(Duration.ofSeconds(30))))
                .build();
        // Uploads and zips of whole albums take longer
        this.transfer = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(IMMICH_HTTP.build(HttpClientSettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(Duration.ofMinutes(30))))
                .build();
        this.keys = keys;
    }

    static @Nullable LocalDateTime localDateTime(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Immich gives local time as if it were UTC
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    static @Nullable Instant instant(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    private static <T> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final RestClientException e) {
            throw new ImmichUnavailableException(e);
        }
    }

    private Consumer<HttpHeaders> apiKey() {
        final String key = keys.apiKey();

        return headers -> headers.set(API_KEY_HEADER, key);
    }

    private static Consumer<HttpHeaders> bearer(final String token) {
        return headers -> headers.setBearerAuth(token);
    }

    // ---------------------------------------------------------------------------------------------- set up

    /**
     * Whether Immich answers at all.
     */
    public boolean ping() {
        try {
            rest.get().uri("/server/ping").retrieve().toBodilessEntity();
            return true;
        } catch (final RestClientException e) {
            return false;
        }
    }

    /**
     * Whether Immich has its admin user.
     */
    public boolean isInitialized() {
        return call(() -> rest.get().uri("/server/config").retrieve().body(JsonNode.class)).path("isInitialized").asBoolean(false);
    }

    public void signUpAdmin(final String email, final String name, final String password) {
        call(() -> rest.post().uri("/auth/admin-sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "name", name, "password", password))
                .retrieve().toBodilessEntity());
    }

    /**
     * @return an access token for the admin's session
     */
    public String login(final String email, final String password) {
        return call(() -> rest.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().body(JsonNode.class)).path("accessToken").asString();
    }

    public void logout(final String token) {
        call(() -> rest.post().uri("/auth/logout").headers(bearer(token)).retrieve().toBodilessEntity());
    }

    public JsonNode systemConfig(final String token) {
        return call(() -> rest.get().uri("/system-config").headers(bearer(token)).retrieve().body(JsonNode.class));
    }

    public void updateSystemConfig(final String token, final JsonNode config) {
        call(() -> rest.put().uri("/system-config").headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(config)
                .retrieve().toBodilessEntity());
    }

    public List<ApiKey> apiKeys(final String token) {
        final List<ApiKey> result = new ArrayList<>();

        for (final JsonNode key : call(() -> rest.get().uri("/api-keys").headers(bearer(token)).retrieve().body(JsonNode.class))) {
            result.add(ApiKey.of(key));
        }
        return result;
    }

    /**
     * @return the new key's secret, which Immich shows only once
     */
    public String createApiKey(final String token, final String name, final List<String> permissions) {
        return call(() -> rest.post().uri("/api-keys").headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "permissions", permissions))
                .retrieve().body(JsonNode.class)).path("secret").asString();
    }

    public void deleteApiKey(final String token, final UUID id) {
        call(() -> rest.delete().uri("/api-keys/{id}", id).headers(bearer(token)).retrieve().toBodilessEntity());
    }

    /**
     * The key as Immich knows it, or empty when it does not, e.g. after its database was replaced.
     */
    public Optional<ApiKey> apiKey(final String key) {
        try {
            return Optional.of(ApiKey.of(rest.get().uri("/api-keys/me").header(API_KEY_HEADER, key).retrieve().body(JsonNode.class)));
        } catch (final HttpClientErrorException e) {
            return Optional.empty();
        } catch (final RestClientException e) {
            throw new ImmichUnavailableException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------- assets

    /**
     * Uploads a file. Immich recognises a file it already has by its checksum and answers with the existing asset.
     *
     * @throws ImmichRejectedException when Immich does not accept the file, e.g. it is not a photo or video
     */
    public Upload upload(final Path file, final String fileName, final Instant created, final Instant modified) {
        final MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        final HttpHeaders fileHeaders = new HttpHeaders();

        fileHeaders.setContentDispositionFormData("assetData", fileName);
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        parts.add("assetData", new HttpEntity<>(new FileSystemResource(file), fileHeaders));
        parts.add("filename", fileName);
        parts.add("fileCreatedAt", created.toString());
        parts.add("fileModifiedAt", modified.toString());
        try {
            final JsonNode response = transfer.post().uri("/assets").headers(apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve().body(JsonNode.class);

            return new Upload(UUID.fromString(response.path("id").asString()), "duplicate".equals(response.path("status").asString()));
        } catch (final HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400) {
                throw new ImmichRejectedException(message(e));
            }
            throw new ImmichUnavailableException(e);
        } catch (final RestClientException e) {
            throw new ImmichUnavailableException(e);
        }
    }

    private static String message(final HttpClientErrorException e) {
        try {
            final JsonNode body = e.getResponseBodyAs(JsonNode.class);

            return body == null ? e.getStatusText() : body.path("message").asString(e.getStatusText());
        } catch (final RuntimeException ignored) {
            return e.getStatusText();
        }
    }

    /**
     * The asset, or empty when Immich does not have it.
     */
    public Optional<Asset> asset(final UUID id) {
        try {
            return Optional.of(Asset.of(rest.get().uri("/assets/{id}", id).headers(apiKey()).retrieve().body(JsonNode.class)));
        } catch (final HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new ImmichUnavailableException(e);
        } catch (final RestClientException e) {
            throw new ImmichUnavailableException(e);
        }
    }

    /**
     * Whether Immich has transcoded the video.
     */
    public boolean isEncoded(final UUID id) {
        final JsonNode items = search(new MetadataSearch(1, null, Map.of("id", Map.of("eq", id.toString()), "isEncoded", Map.of("eq", true)), false))
                .path("assets").path("items");

        return !items.isEmpty();
    }

    /**
     * Every asset Immich has, page by page, oldest first, with its metadata.
     */
    public void forEachAsset(final Consumer<Asset> consumer) {
        String cursor = null;

        do {
            final JsonNode page = search(new MetadataSearch(PAGE, cursor, null, true)).path("assets");

            for (final JsonNode asset : page.path("items")) {
                consumer.accept(Asset.of(asset));
            }
            cursor = page.path("nextCursor").isString() ? page.path("nextCursor").asString() : null;
        } while (cursor != null);
    }

    private JsonNode search(final MetadataSearch search) {
        return call(() -> rest.post().uri("/search/metadata").headers(apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(search)
                .retrieve().body(JsonNode.class));
    }

    /**
     * Search by content: the assets that best match the words, a page at a time.
     *
     * @throws ImmichUnavailableException also when Immich's search by content is turned off
     */
    public SmartPage smartSearch(final String query, final String language, final int page, final int size) {
        final JsonNode assets = call(() -> rest.post().uri("/search/smart").headers(apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "language", language, "page", page, "size", size))
                .retrieve().body(JsonNode.class)).path("assets");
        final List<UUID> ids = new ArrayList<>();

        assets.path("items").forEach(asset -> ids.add(UUID.fromString(asset.path("id").asString())));
        return new SmartPage(ids, assets.path("nextPage").isString());
    }

    /**
     * Deletes assets with their files at once, past Immich's trash.
     */
    public void delete(final List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        call(() -> rest.method(org.springframework.http.HttpMethod.DELETE).uri("/assets").headers(apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", ids, "force", true))
                .retrieve().toBodilessEntity());
    }

    /**
     * Writes a zip of the originals to the stream.
     */
    public void downloadArchive(final List<UUID> ids, final OutputStream out) {
        call(() -> transfer.post().uri("/download/archive").headers(apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("assetIds", ids))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new ImmichUnavailableException(new IOException("Immich answered " + response.getStatusCode()));
                    }
                    try {
                        response.getBody().transferTo(out);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return null;
                }));
    }

    /**
     * Starts one of Immich's jobs again for the assets, e.g. making the thumbnails.
     */
    public void runAssetJob(final List<UUID> ids, final String job) {
        call(() -> rest.post().uri("/assets/jobs").headers(apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("assetIds", ids, "name", job))
                .retrieve().toBodilessEntity());
    }

    // ---------------------------------------------------------------------------------------------- status

    public List<Queue> queues() {
        final List<Queue> queues = new ArrayList<>();

        for (final JsonNode queue : call(() -> rest.get().uri("/queues").headers(apiKey()).retrieve().body(JsonNode.class))) {
            final JsonNode statistics = queue.path("statistics");

            queues.add(new Queue(queue.path("name").asString(), queue.path("isPaused").asBoolean(false),
                    statistics.path("active").asLong(0), statistics.path("waiting").asLong(0) + statistics.path("delayed").asLong(0),
                    statistics.path("failed").asLong(0)));
        }
        return queues;
    }

    public String version() {
        return call(() -> rest.get().uri("/server/about").headers(apiKey()).retrieve().body(JsonNode.class)).path("version").asString("");
    }

    public Storage storage() {
        final JsonNode storage = call(() -> rest.get().uri("/server/storage").headers(apiKey()).retrieve().body(JsonNode.class));

        return new Storage(storage.path("diskUseRaw").asLong(0), storage.path("diskAvailableRaw").asLong(0),
                storage.path("diskSizeRaw").asLong(0));
    }

    public record ApiKey(UUID id, String name, Set<String> permissions) {

        static ApiKey of(final JsonNode key) {
            final Set<String> permissions = new HashSet<>();

            key.path("permissions").forEach(permission -> permissions.add(permission.asString()));
            return new ApiKey(UUID.fromString(key.path("id").asString()), key.path("name").asString(""), permissions);
        }
    }

    /**
     * @param id        the asset, new or the one Immich already had
     * @param duplicate whether Immich already had the file
     */
    public record Upload(UUID id, boolean duplicate) {
    }

    public record SmartPage(List<UUID> ids, boolean hasMore) {
    }

    public record Queue(String name, boolean paused, long active, long waiting, long failed) {
    }

    public record Storage(long used, long available, long size) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MetadataSearch(int size, @Nullable String cursor, @Nullable Map<String, Object> filter, boolean withExif) {
    }

    /**
     * What Spexgram reads of an asset.
     *
     * @param createdAt when Immich got it
     * @param thumbhash null until Immich has made the thumbnail
     * @param takenAt   when it was taken, in local time
     */
    public record Asset(UUID id, boolean video, @Nullable Instant createdAt, @Nullable String thumbhash, @Nullable LocalDateTime takenAt,
                        @Nullable Integer width, @Nullable Integer height, @Nullable Long durationMs,
                        @Nullable Long fileSize, @Nullable Double latitude, @Nullable Double longitude,
                        String originalFileName) {

        static Asset of(final JsonNode asset) {
            final JsonNode exif = asset.path("exifInfo");

            return new Asset(UUID.fromString(asset.path("id").asString()),
                    "VIDEO".equals(asset.path("type").asString()),
                    instant(asset.path("createdAt").asString(null)),
                    asset.path("thumbhash").isString() ? asset.path("thumbhash").asString() : null,
                    localDateTime(asset.path("localDateTime").asString(null)),
                    asset.path("width").isNumber() ? asset.path("width").asInt() : null,
                    asset.path("height").isNumber() ? asset.path("height").asInt() : null,
                    asset.path("duration").isNumber() ? asset.path("duration").asLong() : null,
                    exif.path("fileSizeInByte").isNumber() ? exif.path("fileSizeInByte").asLong() : null,
                    exif.path("latitude").isNumber() ? exif.path("latitude").asDouble() : null,
                    exif.path("longitude").isNumber() ? exif.path("longitude").asDouble() : null,
                    asset.path("originalFileName").asString(""));
        }

        public boolean hasThumbnail() {
            return thumbhash != null;
        }
    }
}
