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

import nu.fgv.spexgram.server.TestCallers;
import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.contribution.ContributionQuota;
import nu.fgv.spexgram.server.settings.Settings;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resumable uploads: who may upload, offsets, sizes and clean-up.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private final Map<String, UploadSession> sessions = new HashMap<>();
    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @Mock
    private UploadSessionRepository repository;
    @Mock
    private ContributionQuota quota;
    @Mock
    private SettingsService settingsService;
    @TempDir
    private Path folder;
    private UploadService service;

    @BeforeEach
    void setUp() {
        final SpexgramProperties properties = new SpexgramProperties(null, null, null, null,
                new SpexgramProperties.Media(null, null, null, folder, DataSize.ofBytes(100)), null, null, null, null, null);

        lenient().when(settingsService.current()).thenReturn(new Settings());
        service = new UploadService(repository, properties, settingsService, quota, clock);
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            final UploadSession session = invocation.getArgument(0);

            sessions.put(session.getId(), session);
            return session;
        });
        lenient().when(repository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(sessions.get(invocation.<String>getArgument(0))));
    }

    @Test
    void membersUploadWithinTheirContributionLimitAndNothingAboveTheLimit() throws Exception {
        assertThatThrownBy(() -> service.create(TestCallers.noAccess(), 10, "a.mkv", null)).isInstanceOf(AccessDeniedException.class);
        doThrow(new BadRequestException("error.contribution.limitBytes", 20)).when(quota).requireBytes("s-viewer", 50);
        assertThatThrownBy(() -> service.create(TestCallers.viewer(), 50, "a.jpg", null)).isInstanceOf(BadRequestException.class);
        assertThat(service.create(TestCallers.viewer(), 10, "a.jpg", Instant.parse("2019-02-14T20:00:00Z")).getLastModified())
                .isEqualTo(Instant.parse("2019-02-14T20:00:00Z"));
        assertThatThrownBy(() -> service.create(TestCallers.editor(), 101, "a.mkv", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(TestCallers.editor(), 0, "a.mkv", null)).isInstanceOf(BadRequestException.class);
        assertThat(service.maxSize()).isEqualTo(100);

        // The admin's setting applies within the deployment's limit
        final Settings small = new Settings();

        small.setMaxUploadGb(1);
        when(settingsService.current()).thenReturn(small);
        assertThat(service.maxSize()).isEqualTo(100);
    }

    @Test
    void whatABrokenOffChunkWroteIsDroppedWhenTheNextOneComes() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 6, "a.mp4", null);

        service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        // A chunk that broke off after two bytes: the bytes are on disk, but the offset does not count them
        Files.write(folder.resolve(session.getId()), new byte[]{8, 8}, java.nio.file.StandardOpenOption.APPEND);
        assertThat(service.append(TestCallers.editor(), session.getId(), 3, new ByteArrayInputStream(new byte[]{4, 5, 6}))).isEqualTo(6);

        assertThat(Files.readAllBytes(folder.resolve(session.getId()))).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void chunksAreAppendedAtTheOffsetUntilTheUploadIsComplete() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 6, "a".repeat(300), null);

        assertThat(session.getFileName()).hasSize(255);
        assertThat(service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{1, 2, 3}))).isEqualTo(3);
        assertThatThrownBy(() -> service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[]{9})))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.claim(session.getId(), TestCallers.editor())).isInstanceOf(BadRequestException.class);
        assertThat(service.append(TestCallers.editor(), session.getId(), 3, new ByteArrayInputStream(new byte[]{4, 5, 6}))).isEqualTo(6);

        assertThat(session.getCompletedAt()).isEqualTo(clock.instant());
        assertThat(Files.readAllBytes(service.claim(session.getId(), TestCallers.editor()).file())).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void aChunkBeyondTheDeclaredLengthIsRefused() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.mkv", null);

        assertThatThrownBy(() -> service.append(TestCallers.editor(), session.getId(), 0, new ByteArrayInputStream(new byte[3])))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUploadIsOnlyItsOwners() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.mkv", null);

        assertThatThrownBy(() -> service.get(TestCallers.admin(), session.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.terminate(TestCallers.admin(), session.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get(TestCallers.editor(), session.getId())).isSameAs(session);
    }

    @Test
    void terminatingDeletesTheFile() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, null, null);

        service.terminate(TestCallers.editor(), session.getId());

        assertThat(folder.resolve(session.getId())).doesNotExist();
        verify(repository).delete(session);
    }

    @Test
    void uploadsOlderThanADayAreCleanedUp() throws Exception {
        final UploadSession session = service.create(TestCallers.editor(), 2, "a.mkv", null);

        clock.advance(Duration.ofHours(25));
        when(repository.findByCreatedAtBefore(clock.instant().minus(Duration.ofHours(24)))).thenReturn(List.of(session));
        service.cleanUp();

        verify(repository).deleteById(session.getId());
        assertThat(folder.resolve(session.getId())).doesNotExist();
    }

    @Test
    void wipeEmptiesTheFolderButKeepsIt() throws Exception {
        Files.writeString(folder.resolve("file"), "data");
        Files.createDirectories(folder.resolve("nested"));
        Files.writeString(folder.resolve("nested").resolve("file"), "data");

        service.wipe();

        verify(repository).deleteAllInBatch();
        assertThat(folder).isEmptyDirectory();
    }
}
