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

import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Immich's three API keys, each with only the permissions it needs (section 8 of the spec), kept as files in
 * {@code spexgram.immich.keys-dir}. Immich makes a key's secret and shows it only once, so the API creates the keys
 * ({@link ImmichBootstrap}) and hands them over through this folder: the media server mounts it read-only and reads
 * {@code media.key}; admins read {@code bulk-import.key} on the host for Immich's command line tool.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ImmichKeys {

    private final Path folder;
    private volatile @Nullable String apiKey;

    public ImmichKeys(final SpexgramProperties properties) {
        this.folder = properties.immich().keysDir();
    }

    /**
     * The API's key, once {@link ImmichBootstrap} has made sure of it.
     *
     * @throws ImmichUnavailableException before then
     */
    public String apiKey() {
        final String key = apiKey;

        if (key == null) {
            throw new ImmichUnavailableException(new IllegalStateException("Immich is not set up yet"));
        }
        return key;
    }

    public boolean isReady() {
        return apiKey != null;
    }

    void useApiKey(final String key) {
        apiKey = key;
    }

    Optional<String> read(final Purpose purpose) throws IOException {
        final Path file = folder.resolve(purpose.fileName());

        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        final String key = Files.readString(file, StandardCharsets.UTF_8).strip();

        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    /**
     * Writes the key by replacing the file, so that a reader never sees half a key.
     */
    void write(final Purpose purpose, final String key) throws IOException {
        Files.createDirectories(folder);

        final Path temporary = Files.createTempFile(folder, purpose.fileName(), ".tmp");

        Files.writeString(temporary, key, StandardCharsets.UTF_8);
        Files.move(temporary, folder.resolve(purpose.fileName()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * What a key is for, its name in Immich and its permissions.
     */
    public enum Purpose {
        /**
         * The API's own: uploads, reading, editing and deleting assets, search, zips, Immich's jobs and status.
         */
        API("spexgram-api", "api.key", List.of("asset.read", "asset.view", "asset.download", "asset.upload",
                "asset.update", "asset.delete", "job.create", "queue.read", "server.about", "server.storage")),
        /**
         * The media server's: reading thumbnails, previews, originals and videos only.
         */
        MEDIA("spexgram-media", "media.key", List.of("asset.read", "asset.view", "asset.download")),
        /**
         * Admins' bulk imports with Immich's command line tool: uploads only (5.6).
         */
        BULK_IMPORT("spexgram-bulk-import", "bulk-import.key", List.of("asset.upload"));

        private final String keyName;
        private final String fileName;
        private final List<String> permissions;

        Purpose(final String keyName, final String fileName, final List<String> permissions) {
            this.keyName = keyName;
            this.fileName = fileName;
            this.permissions = permissions;
        }

        public String keyName() {
            return keyName;
        }

        public String fileName() {
            return fileName;
        }

        public List<String> permissions() {
            return permissions;
        }
    }
}
