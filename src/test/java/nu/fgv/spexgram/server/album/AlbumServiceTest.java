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
package nu.fgv.spexgram.server.album;

import nu.fgv.spexgram.server.TestCallers;
import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.spex.SpexService;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Memories of spex whose premiere is near today's date, and the places of albums, as the explore page shows them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class AlbumServiceTest {

    private static final Viewer MEMBER = new Viewer(Viewer.Kind.USER, TestCallers.viewer());

    @Mock
    private AlbumRepository repository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private AlbumKindService kindService;
    @Mock
    private SpexService spexService;
    @Mock
    private ViewerService viewerService;
    @Mock
    private AuditService auditService;
    @Mock
    private ImmichClient immichClient;

    private AlbumService service;

    private static Album album(final long id, final String title, final String place, final LocalDate... premieres) {
        final Album album = new Album();
        final AlbumKind kind = new AlbumKind();

        kind.setUuid(uuid(900));
        kind.setNameSv("Föreställning");
        kind.setNameEn("Show");
        album.setId(id);
        album.setUuid(uuid((int) id));
        album.setTitle(title);
        album.setKind(kind);
        album.setPlace(place);
        album.setVisibility(Visibility.MEMBERS);
        for (final LocalDate premiere : premieres) {
            final SpexRef ref = new SpexRef();

            ref.setId(uuid(500 + (int) id));
            ref.setTitle(title);
            ref.setYear(premiere.getYear());
            ref.setPremiere(premiere);
            album.getSpex().add(ref);
        }
        return album;
    }

    private static <T> Specification<T> anySpec() {
        return ArgumentMatchers.any();
    }

    @BeforeEach
    void setUp() {
        service = new AlbumService(repository, itemRepository, kindService, spexService, viewerService, auditService,
                immichClient, new TestClock(Instant.parse("2026-11-17T10:00:00Z")));
        lenient().when(viewerService.current()).thenReturn(MEMBER);
    }

    @Test
    void aPremiereIsNearTodayAcrossTheTurnOfTheYearAndOnTheTwentyNinthOfFebruary() {
        assertThat(AlbumService.daysFromPremiere(LocalDate.of(1984, 1, 2), LocalDate.of(2026, 12, 30))).isEqualTo(3);
        assertThat(AlbumService.daysFromPremiere(LocalDate.of(1984, 11, 20), LocalDate.of(2026, 11, 17))).isEqualTo(3);
        assertThat(AlbumService.daysFromPremiere(LocalDate.of(1984, 2, 29), LocalDate.of(2027, 2, 28))).isZero();
    }

    @Test
    void albumsOfSpexWhosePremiereIsNearTodayInEarlierYearsAreRememberedNearestFirst() {
        final Album near = album(1, "Nobel", "Kårhuset", LocalDate.of(1975, 11, 17));
        final Album nearer = album(2, "Cleopatra", "Kårhuset", LocalDate.of(1990, 11, 19));
        final Album nearAndNewer = album(3, "Charles", "Kårhuset", LocalDate.of(2001, 11, 15));
        final Album far = album(4, "Fiesco", "Kårhuset", LocalDate.of(1980, 3, 1));
        final Album thisYear = album(5, "Anno 2026", "Kårhuset", LocalDate.of(2026, 11, 17));
        final Album none = album(6, "Jubileum", "Kårhuset");

        when(repository.findAll(AlbumServiceTest.<Album>anySpec())).thenReturn(List.of(near, nearer, nearAndNewer, far, thisYear, none));

        // Two days off either way: the newer spex first
        assertThat(service.premiereAlbums(7, 12)).extracting(AlbumDtos.AlbumDto::title)
                .containsExactly("Nobel", "Charles", "Cleopatra");
        assertThat(service.premiereAlbums(0, 12)).extracting(AlbumDtos.AlbumDto::title).containsExactly("Nobel");
        assertThat(service.premiereAlbums(7, 1)).extracting(AlbumDtos.AlbumDto::title).containsExactly("Nobel");
    }

    @Test
    void placesThatDifferOnlyInCaseAreOnePlaceMostFirst() {
        when(repository.findAll(AlbumServiceTest.<Album>anySpec())).thenReturn(List.of(
                album(1, "A", "Kårhuset"), album(2, "B", "kårhuset "), album(3, "C", "Kårhuset"),
                album(4, "D", "Lisebergshallen"), album(5, "E", " ")));

        assertThat(service.places()).containsExactly(new AlbumDtos.PlaceDto("Kårhuset", 3), new AlbumDtos.PlaceDto("Lisebergshallen", 1));
    }
}
