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

package nu.fgv.spexgram.server.imports;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Files received through the import API, by their SHA-256 ({@code imports/files/<sha256>}). A file is kept until no
 * batch has used it for {@code keep-files} (Spexipedia's docs/content-import.md, section 4).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class ImportFileStore {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path files;
    private final SpexgramProperties properties;
    private final Clock clock;

    public ImportFileStore(final SpexgramProperties properties, final Clock clock) {
        this.files = properties.imports().root().resolve("files").toAbsolutePath().normalize();
        this.properties = properties;
        this.clock = clock;
    }

    public static String normalize(final String sha256) {
        final String lower = sha256.strip().toLowerCase(Locale.ROOT);

        if (!SHA256.matcher(lower).matches()) {
            throw new BadRequestException("error.field.invalid", "sha256");
        }
        return lower;
    }

    public boolean has(final String sha256) {
        return Files.isRegularFile(path(sha256));
    }

    /**
     * Stores the content under its hash; refused when the content has another hash. Returns false when it was there.
     */
    public boolean put(final String sha256, final InputStream content) {
        final String hash = normalize(sha256);
        final Path target = path(hash);

        if (Files.isRegularFile(target)) {
            touch(target);
            return false;
        }
        try {
            Files.createDirectories(files);

            final Path temporary = Files.createTempFile(files, "upload-", ".part");

            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");

                try (DigestInputStream in = new DigestInputStream(content, digest)) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) {
                    throw new BadRequestException("error.import.hash", hash);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The file of a hash, for a batch that uses it; a bad request when it has not been uploaded.
     */
    public Path require(final String sha256) {
        final Path path = path(normalize(sha256));

        if (!Files.isRegularFile(path)) {
            throw new BadRequestException("error.import.file", sha256);
        }
        touch(path);
        return path;
    }

    private Path path(final String sha256) {
        return files.resolve(normalize(sha256));
    }

    private void touch(final Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.from(clock.instant()));
        } catch (final IOException e) {
            log.debug("Could not touch {}: {}", path, e.getMessage());
        }
    }

    /**
     * Deletes files no batch has used for {@code keep-files}. Part of the maintenance job UPLOAD_CLEANUP.
     */
    public void cleanUp() {
        if (!Files.isDirectory(files)) {
            return;
        }

        final long before = clock.instant().minus(properties.imports().keepFiles()).toEpochMilli();

        try (Stream<Path> paths = Files.list(files)) {
            paths.filter(path -> {
                try {
                    return Files.getLastModifiedTime(path).toMillis() < before;
                } catch (final IOException e) {
                    return false;
                }
            }).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (final IOException e) {
            log.warn("Could not clean up {}: {}", files, e.getMessage());
        }
    }
}
