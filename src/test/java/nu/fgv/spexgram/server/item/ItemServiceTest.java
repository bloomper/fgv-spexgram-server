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
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An album's items, count and cover as Spexipedia's service account sees them, and covers that follow their items.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    private static final Viewer SPEXIPEDIA = new Viewer(Viewer.Kind.SPEXIPEDIA, TestCallers.spexipediaService());

    @Mock
    private ItemRepository repository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private AlbumService albumService;
    @Mock
    private ViewerService viewerService;
    @Mock
    private ItemViews views;
    @Mock
    private AuditService auditService;
    @Mock
    private ImmichClient immichClient;
    @Mock
    private ItemIntake intake;

    private ItemService service;

    private static Album album(final long id) {
        final Album album = new Album();

        album.setId(id);
        album.setUuid(uuid((int) id));
        album.setVisibility(Visibility.PUBLIC);
        return album;
    }

    private static Item item(final long id, final Album album) {
        final Item item = new Item();

        item.setId(id);
        item.setUuid(uuid(100 + (int) id));
        item.setAlbum(album);
        item.setVisibility(Visibility.PUBLIC);
        item.setStatus(ItemStatus.READY);
        return item;
    }

    private static <T> T anySpec() {
        return any();
    }

    @BeforeEach
    void setUp() {
        service = new ItemService(repository, albumRepository, albumService, viewerService, views, auditService, immichClient,
                intake, Clock.systemUTC());
        lenient().when(viewerService.currentForSpexipedia()).thenReturn(SPEXIPEDIA);
    }

    @Test
    void anAlbumsCountResolvesAsSpexipediaForItsServiceAccount() {
        final Album album = album(1);

        when(albumRepository.findByUuidIn(List.of(album.getUuid()))).thenReturn(List.of(album));
        when(repository.countByAlbum(ItemServiceTest.<Specification<Item>>anySpec(), eq(List.of(album)))).thenReturn(Map.of(album.getId(), 3L));

        assertThat(service.itemCounts(List.of(album.getUuid()))).isEqualTo(Map.of(album.getUuid(), 3));
        verify(viewerService, never()).current();
    }

    @Test
    void albumsWithoutVisibleItemsCountZeroAndUnknownOnesAreLeftOut() {
        final Album album = album(1);

        when(albumRepository.findByUuidIn(List.of(album.getUuid(), uuid(99)))).thenReturn(List.of(album));
        when(repository.countByAlbum(ItemServiceTest.<Specification<Item>>anySpec(), eq(List.of(album)))).thenReturn(Map.of());

        assertThat(service.itemCounts(List.of(album.getUuid(), uuid(99)))).isEqualTo(Map.of(album.getUuid(), 0));
    }

    @Test
    void aCoverThatWasMovedToAnotherAlbumIsNotUsed() {
        final Album album = album(1);
        final Item moved = item(7, album(2));
        final Item first = item(8, album);

        album.setCoverItemId(moved.getId());
        when(albumRepository.findByUuidIn(List.of(album.getUuid()))).thenReturn(List.of(album));
        when(repository.findAllById(Set.of(moved.getId()))).thenReturn(List.of(moved));
        when(repository.firstByAlbum(ItemServiceTest.<Specification<Item>>anySpec(), eq(List.of(album)))).thenReturn(Map.of(album.getId(), first.getId()));
        when(repository.findAllById(List.of(first.getId()))).thenReturn(List.of(first));

        assertThat(service.covers(List.of(album.getUuid()))).containsOnlyKeys(album.getUuid());

        verify(views).toDto(eq(first), ArgumentMatchers.<ItemViews.Context>any());
        verify(viewerService, never()).current();
    }

    @Test
    void aVisibleChosenCoverIsUsedWithoutLookingForTheFirstItem() {
        final Album album = album(1);
        final Item chosen = item(7, album);

        album.setCoverItemId(chosen.getId());
        when(albumRepository.findByUuidIn(List.of(album.getUuid()))).thenReturn(List.of(album));
        when(repository.findAllById(Set.of(chosen.getId()))).thenReturn(List.of(chosen));
        when(repository.firstByAlbum(ItemServiceTest.<Specification<Item>>anySpec(), eq(List.of()))).thenReturn(Map.of());

        assertThat(service.covers(List.of(album.getUuid()))).containsOnlyKeys(album.getUuid());

        verify(views).toDto(eq(chosen), ArgumentMatchers.<ItemViews.Context>any());
    }

    @Test
    void anItemMovedToAnotherAlbumStopsBeingItsOldAlbumsCover() {
        final Album old = album(1);
        final Album other = album(2);
        final Item item = item(7, old);

        old.setCoverItemId(item.getId());
        ItemService.moveTo(item, other);

        assertThat(item.getAlbum()).isSameAs(other);
        assertThat(old.getCoverItemId()).isNull();
    }
}
