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

package nu.fgv.spexgram.server.spex;

import nu.fgv.spexgram.server.TestCallers;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.SpexRef;
import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.SpexregisterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spex read from Spexregister: searching, single spex, and copying them onto albums.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SpexServiceTest {

    private static final SpexDto NOBEL = new SpexDto(uuid(1), "Nobel", 1975, false, null, uuid(1), "Chalmersspexet");
    private static final SpexDto NOBEL_REVIVAL = new SpexDto(uuid(2), "Nobel", 2026, true, uuid(1), uuid(1), "Chalmersspexet");
    private static final SpexDto CLEOPATRA = new SpexDto(uuid(3), "Cleopatra", 2024, false, null, uuid(1), "Chalmersspexet");
    @Mock
    private SpexregisterClient client;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private CallerService callerService;
    private SpexService service;

    @BeforeEach
    void setUp() {
        service = new SpexService(client, albumRepository, callerService, new TestClock(Instant.parse("2026-11-01T10:00:00Z")));
        lenient().when(callerService.current()).thenReturn(TestCallers.editor());
    }

    @Test
    void searchMatchesTitleOrYearWithRevivalsRightAfterTheirOriginal() {
        when(client.allSpex()).thenReturn(List.of(NOBEL, CLEOPATRA, NOBEL_REVIVAL));

        assertThat(service.search(" NOB ")).containsExactly(NOBEL, NOBEL_REVIVAL);
        assertThat(service.search("2024")).containsExactly(CLEOPATRA);
        assertThat(service.search("")).containsExactly(CLEOPATRA, NOBEL, NOBEL_REVIVAL);
        assertThat(service.search("2026")).as("a revival brings its original").containsExactly(NOBEL, NOBEL_REVIVAL);
    }

    @Test
    void searchIsForEditorsAndFailsWhileSpexregisterIsDown() {
        when(callerService.current()).thenReturn(TestCallers.viewer());
        assertThatThrownBy(() -> service.search("nob")).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(client.allSpex()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));
        assertThatThrownBy(() -> service.search("nob")).isInstanceOf(SpexregisterUnavailableException.class);
    }

    @Test
    void searchShowsAtMostAPage() {
        when(client.allSpex()).thenReturn(IntStream.range(0, 200)
                .mapToObj(i -> new SpexDto(uuid(i), "Spex " + i, 1900 + i, false, null, null, null)).toList());

        assertThat(service.search("spex")).hasSizeLessThan(200);
    }

    @Test
    void anUnknownSpexIsABadRequest() {
        when(client.spexById(uuid(9))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byId(uuid(9))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aSpexIsCopiedOntoAnAlbumWithTheTimeOfCopying() {
        final SpexRef ref = new SpexRef();

        service.copy(NOBEL_REVIVAL, ref);

        assertThat(ref.getId()).isEqualTo(uuid(2));
        assertThat(ref.familyId()).isEqualTo(uuid(1));
        assertThat(ref.getTitle()).isEqualTo("Nobel");
        assertThat(ref.getYear()).isEqualTo(2026);
        assertThat(ref.getRevival()).isTrue();
        assertThat(ref.getCategoryName()).isEqualTo("Chalmersspexet");
        assertThat(ref.getRefreshedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void theNightlyRefreshUpdatesEveryAlbumsCopyOfItsSpex() {
        final Album album = new Album();
        final SpexRef nobel = new SpexRef();
        final SpexRef revival = new SpexRef();

        nobel.setId(uuid(1));
        nobel.setTitle("Nobel (gammal titel)");
        revival.setId(uuid(2));
        album.getSpex().addAll(List.of(nobel, revival));
        when(albumRepository.findWithSpex()).thenReturn(List.of(album));
        when(client.allSpex()).thenReturn(List.of(NOBEL, NOBEL_REVIVAL));

        service.refresh();

        assertThat(nobel.getTitle()).isEqualTo("Nobel");
        assertThat(revival.getParentId()).isEqualTo(uuid(1));
    }

    @Test
    void theRefreshDoesNothingWithoutAlbumsAndSurvivesSpexregisterBeingDown() {
        when(albumRepository.findWithSpex()).thenReturn(List.of());
        service.refresh();
        verify(client, never()).allSpex();

        when(albumRepository.findWithSpex()).thenReturn(List.of(new Album()));
        when(client.allSpex()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));
        service.refresh();
    }
}
