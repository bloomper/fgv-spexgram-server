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
package nu.fgv.spexgram.server.item;

import nu.fgv.spexgram.server.TestCallers;
import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.item.ItemCounts.MonthCount;
import nu.fgv.spexgram.server.item.ItemDtos.TimelineMonth;
import nu.fgv.spexgram.server.user.AppUser;
import nu.fgv.spexgram.server.user.AppUserRepository;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The timeline's months and members' favourites: personal, only of items the member may see, never for visitors.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class TimelineServiceTest {

    private static final Viewer MEMBER = new Viewer(Viewer.Kind.USER, TestCallers.viewer());

    @Mock
    private ItemRepository itemRepository;
    @Mock
    private FavouriteRepository repository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private ViewerService viewerService;
    @Mock
    private ItemViews views;

    private FavouriteService favourites;
    private TimelineService timeline;

    private static Item item(final long id, final Visibility visibility) {
        final Album album = new Album();
        final Item item = new Item();

        album.setId(1L);
        album.setVisibility(Visibility.MEMBERS);
        item.setId(id);
        item.setUuid(uuid((int) id));
        item.setAlbum(album);
        item.setVisibility(visibility);
        item.setStatus(ItemStatus.READY);
        return item;
    }

    private static <T> Specification<T> anySpec() {
        return ArgumentMatchers.any();
    }

    @BeforeEach
    void setUp() {
        final AppUser user = new AppUser();

        user.setId(42L);
        favourites = new FavouriteService(repository, itemRepository, userRepository, callerService, viewerService, views,
                new TestClock(Instant.parse("2026-11-17T10:00:00Z")));
        timeline = new TimelineService(itemRepository, viewerService, favourites, views);
        lenient().when(viewerService.current()).thenReturn(MEMBER);
        lenient().when(userRepository.findBySubject("s-viewer")).thenReturn(Optional.of(user));
    }

    @Test
    void theMonthsAreNewestFirstWithTheUndatedOnesAsNull() {
        when(itemRepository.countByMonth(anySpec())).thenReturn(List.of(new MonthCount(YearMonth.of(2024, 2), 12), new MonthCount(null, 3)));

        assertThat(timeline.months(false)).containsExactly(new TimelineMonth("2024-02", 12), new TimelineMonth(null, 3));
    }

    @Test
    void aMonthThatIsNotOneIsRefused() {
        assertThat(TimelineService.month("2024-02")).isEqualTo(YearMonth.of(2024, 2));
        assertThat(TimelineService.month(null)).isNull();
        assertThatThrownBy(() -> TimelineService.month("februari")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void visitorsHaveNoFavourites() {
        when(viewerService.current()).thenReturn(Viewer.visitor());

        assertThat(favourites.marked(List.of(uuid(1)))).isEmpty();
        assertThatThrownBy(() -> favourites.set(List.of(uuid(1)), true)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> timeline.months(true)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMemberMarksOnlyItemsTheyMaySeeAndOnlyOnce() {
        final Item seen = item(1, Visibility.MEMBERS);
        final Item marked = item(2, Visibility.PUBLIC);
        final Item unpublished = item(3, Visibility.UNPUBLISHED);
        final ArgumentCaptor<List<Favourite>> saved = ArgumentCaptor.forClass(List.class);

        when(itemRepository.findByUuidIn(List.of(uuid(1), uuid(2), uuid(3)))).thenReturn(List.of(seen, marked, unpublished));
        when(repository.findItemIds(42L, List.of(1L, 2L))).thenReturn(Set.of(2L));

        favourites.set(List.of(uuid(1), uuid(2), uuid(3)), true);

        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(favourite -> {
            assertThat(favourite.getUserId()).isEqualTo(42L);
            assertThat(favourite.getItemId()).isEqualTo(1L);
        });

        favourites.set(List.of(uuid(1), uuid(2), uuid(3)), false);
        verify(repository).deleteItems(42L, List.of(1L, 2L));
        verify(repository, never()).deleteItems(42L, List.of(1L, 2L, 3L));
    }

    @Test
    void theMembersFavouritesAreThoseTheyMarked() {
        when(repository.findItemUuids(ArgumentMatchers.eq(42L), anyCollection())).thenReturn(Set.of(uuid(2)));

        assertThat(favourites.marked(List.of(uuid(1), uuid(2)))).containsExactly(uuid(2));
    }
}
