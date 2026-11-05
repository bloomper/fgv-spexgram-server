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


package nu.fgv.spexgram.server.upload;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.contribution.ContributionQuota;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uploads: created, appended to chunk by chunk, then claimed by the operation that sends the file to Immich. Only the
 * person who started an upload can append to or use it. Editors upload to albums, members contribute within their
 * daily limit (5.4).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class UploadService {

    public static final long MAX_CHUNK = 60L * 1024 * 1024;
    private static final Duration KEEP = Duration.ofHours(24);
    private static final int MAX_FILE_NAME = 255;
    /** Open uploads a member may hold at once; editors are not limited. */
    static final int MAX_OPEN_PER_MEMBER = 10;

    private final UploadSessionRepository repository;
    private final Path folder;
    private final long hardLimit;
    private final SettingsService settingsService;
    private final ContributionQuota contributionQuota;
    private final Clock clock;
    private final Set<String> appending = ConcurrentHashMap.newKeySet();

    public UploadService(final UploadSessionRepository repository, final SpexgramProperties properties,
                         final SettingsService settingsService, final ContributionQuota contributionQuota, final Clock clock) {
        this.repository = repository;
        this.folder = properties.media().uploads();
        this.hardLimit = properties.media().maxUploadSize().toBytes();
        this.settingsService = settingsService;
        this.contributionQuota = contributionQuota;
        this.clock = clock;
    }

    /**
     * The largest upload: the admin's setting, within the deployment's limit.
     */
    public long maxSize() {
        return Math.min(hardLimit, settingsService.current().getMaxUploadGb() * 1024L * 1024 * 1024);
    }

    private Path file(final String id) {
        return folder.resolve(id);
    }

    @Transactional
    public UploadSession create(final Caller caller, final long length, final @Nullable String fileName,
                                final @Nullable Instant lastModified) throws IOException {
        caller.requireUser();
        if (length <= 0 || length > maxSize()) {
            throw new BadRequestException("error.upload.tooLarge");
        }
        if (!caller.isEditor()) {
            // A member cannot hold more than a few uploads open, each counted against the day's quota at its length
            if (repository.countByOwner(caller.subject()) >= MAX_OPEN_PER_MEMBER) {
                throw new BadRequestException("error.upload.tooMany", MAX_OPEN_PER_MEMBER);
            }
            contributionQuota.requireBytes(caller.subject(), length);
        }

        final UploadSession session = new UploadSession();

        session.setId(UUID.randomUUID().toString());
        session.setOwner(caller.subject());
        session.setLength(length);
        session.setOffset(0);
        session.setFileName(fileName == null ? null : fileName.length() > MAX_FILE_NAME ? fileName.substring(0, MAX_FILE_NAME) : fileName);
        session.setLastModified(lastModified);
        session.setCreatedAt(clock.instant());
        Files.createDirectories(folder);
        Files.createFile(file(session.getId()));
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public UploadSession get(final Caller caller, final String id) {
        return owned(caller, id);
    }

    /**
     * Appends a chunk at the given offset, which must be where the upload stands. Runs without a transaction, so that no
     * database connection is held while the chunk streams in; one chunk at a time per upload.
     *
     * @return the new offset
     */
    public long append(final Caller caller, final String id, final long offset, final InputStream body) throws IOException {
        if (!appending.add(id)) {
            throw new ConflictException("error.upload.offset");
        }
        try {
            final UploadSession session = owned(caller, id);

            if (offset != session.getOffset()) {
                throw new ConflictException("error.upload.offset");
            }

            final long remaining = session.getLength() - session.getOffset();
            long written = 0;

            try (FileChannel channel = FileChannel.open(file(id), StandardOpenOption.WRITE)) {
                // Drops what an earlier chunk wrote before it broke off, which the offset does not count
                channel.truncate(session.getOffset());
                channel.position(session.getOffset());

                final OutputStream out = Channels.newOutputStream(channel);
                final byte[] buffer = new byte[64 * 1024];
                int read;

                while ((read = body.read(buffer)) != -1) {
                    if (written + read > remaining || written + read > MAX_CHUNK) {
                        throw new BadRequestException("error.upload.tooLarge");
                    }
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
            session.setOffset(session.getOffset() + written);
            if (session.isComplete()) {
                session.setCompletedAt(clock.instant());
            }
            repository.save(session);
            return session.getOffset();
        } finally {
            appending.remove(id);
        }
    }

    @Transactional
    public void terminate(final Caller caller, final String id) throws IOException {
        final UploadSession session = owned(caller, id);

        Files.deleteIfExists(file(session.getId()));
        repository.delete(session);
    }

    /**
     * A finished upload, for the operation that sends it to Immich and then {@link #release releases} it.
     */
    @Transactional(readOnly = true)
    public Claimed claim(final String id, final Caller caller) {
        final UploadSession session = owned(caller, id);

        if (!session.isComplete()) {
            throw new BadRequestException("error.upload.incomplete");
        }
        return new Claimed(id, file(id), session.getFileName() == null ? id : session.getFileName(),
                session.getLastModified(), session.getLength());
    }

    /**
     * Forgets an upload whose file has been used.
     */
    @Transactional
    public void release(final String id) {
        repository.deleteById(id);
        try {
            Files.deleteIfExists(file(id));
        } catch (final IOException e) {
            log.warn("Could not delete upload {}", id, e);
        }
    }

    @SchedulerLock(name = "upload-clean-up", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanUp() {
        for (final UploadSession session : repository.findByCreatedAtBefore(clock.instant().minus(KEEP))) {
            release(session.getId());
        }
    }

    /**
     * Forgets every upload and deletes their files, for the sample data.
     */
    @Transactional
    public void wipe() throws IOException {
        repository.deleteAllInBatch();
        FileSystemUtils.deleteRecursively(folder);
        Files.createDirectories(folder);
    }

    private UploadSession owned(final Caller caller, final String id) {
        return repository.findById(id)
                .filter(session -> session.getOwner().equals(caller.subject()))
                .orElseThrow(() -> new ResourceNotFoundException("error.upload.notFound", id));
    }

    /**
     * A finished upload's file.
     *
     * @param lastModified when the file was last changed on the uploader's device, if the web said
     */
    public record Claimed(String id, Path file, String fileName, @Nullable Instant lastModified, long length) {
    }
}
