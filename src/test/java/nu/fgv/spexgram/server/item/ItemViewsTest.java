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
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.media.MediaSigner;
import nu.fgv.spexgram.server.settings.Settings;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Who sees an item and what of it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ItemViewsTest {

    private static final Viewer VISITOR = Viewer.visitor();
    private static final Viewer MEMBER = new Viewer(Viewer.Kind.USER, TestCallers.viewer());
    private static final Viewer EDITOR = new Viewer(Viewer.Kind.EDITOR, TestCallers.editor());
    private static final Viewer SPEXIPEDIA = new Viewer(Viewer.Kind.SPEXIPEDIA, TestCallers.spexipediaService());
    private final Settings settings = new Settings();
    @Mock
    private SettingsService settingsService;
    private ItemViews views;

    private static Album album(final Visibility visibility) {
        final Album album = new Album();

        album.setUuid(uuid(10));
        album.setTitle("Cleopatra 2024 – föreställning");
        album.setVisibility(visibility);
        return album;
    }

    private static Item item(final Album album, final Visibility visibility) {
        final Item item = new Item();

        item.setUuid(uuid(1));
        item.setAlbum(album);
        item.setVisibility(visibility);
        item.setStatus(ItemStatus.READY);
        item.setImmichAssetId(uuid(2));
        item.setTakenAt(LocalDateTime.of(2024, 2, 14, 20, 0));
        item.setLatitude(57.689);
        item.setLongitude(11.974);
        item.setUploadedBySubject("s-editor");
        item.setUploadedByName("Erik");
        item.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        return item;
    }

    @BeforeEach
    void setUp() {
        final SpexgramProperties properties = new SpexgramProperties(null, null, null, null,
                new SpexgramProperties.Media("https://media.example.org", "secret", Duration.ofHours(4), null, null),
                null, null, null, null, null);

        lenient().when(settingsService.current()).thenReturn(settings);
        views = new ItemViews(new MediaSigner(properties, new TestClock(Instant.parse("2026-11-05T10:00:00Z"))), settingsService);
    }

    @Test
    void aPublicItemInAnAlbumForMembersIsSeenByVisitorsWithoutItsAlbum() {
        final Item item = item(album(Visibility.MEMBERS), Visibility.PUBLIC);

        assertThat(ItemViews.canSee(item, VISITOR)).isTrue();
        assertThat(ItemViews.canSee(item.getAlbum(), VISITOR)).isFalse();
        assertThat(views.toDto(item, VISITOR).albumId()).isNull();
        assertThat(views.toDto(item, MEMBER).albumId()).isEqualTo(uuid(10));
    }

    @Test
    void visitorsGetNoUploaderPositionOrOriginal() {
        final ItemDto dto = views.toDto(item(album(Visibility.PUBLIC), Visibility.PUBLIC), VISITOR);

        assertThat(dto.uploadedBy()).isNull();
        assertThat(dto.latitude()).isNull();
        assertThat(dto.media()).isNotNull();
        assertThat(dto.media().originalUrl()).isNull();
        assertThat(dto.media().previewUrl()).startsWith("https://media.example.org/a/" + uuid(2) + "/preview/");
    }

    @Test
    void membersSeePositionsAndOriginalsOnlyAsAdminsAllow() {
        final Item item = item(album(Visibility.MEMBERS), Visibility.MEMBERS);

        assertThat(views.toDto(item, MEMBER).latitude()).isEqualTo(57.689);
        assertThat(views.toDto(item, MEMBER).media().originalUrl()).isNull();
        settings.setUserDownloadsOriginals(true);
        settings.setShowPositions(false);
        assertThat(views.toDto(item, MEMBER).latitude()).isNull();
        assertThat(views.toDto(item, MEMBER).media().originalUrl()).contains("/original/");
        assertThat(views.toDto(item, EDITOR).latitude()).isEqualTo(57.689);
        assertThat(views.toDto(item, EDITOR).uploadedBy()).isEqualTo("Erik");
    }

    @Test
    void spexipediaSeesMembersItemsWithThumbnailsAndPreviewsOnly() {
        final Item item = item(album(Visibility.MEMBERS), Visibility.MEMBERS);

        item.setType(ItemType.VIDEO);
        assertThat(ItemViews.canSee(item, SPEXIPEDIA)).isTrue();
        assertThat(ItemViews.canSee(item, VISITOR)).isFalse();
        assertThat(views.toDto(item, SPEXIPEDIA).media().videoUrl()).isNull();
        assertThat(views.toDto(item, SPEXIPEDIA).media().originalUrl()).isNull();
    }

    @Test
    void unpublishedProcessingAndTrashedItemsAreForEditorsAndTheirOwnContributionsForTheContributor() {
        final Item unpublished = item(album(Visibility.PUBLIC), Visibility.UNPUBLISHED);
        final Item processing = item(album(Visibility.PUBLIC), Visibility.PUBLIC);
        final Item trashed = item(album(Visibility.PUBLIC), Visibility.PUBLIC);
        final Item contribution = item(album(Visibility.MEMBERS), Visibility.UNPUBLISHED);

        processing.setStatus(ItemStatus.PROCESSING);
        trashed.setTrashedAt(Instant.now());
        contribution.setContributionStatus(ContributionStatus.WAITING);
        contribution.setUploadedBySubject("s-viewer");
        assertThat(ItemViews.canSee(unpublished, MEMBER)).isFalse();
        assertThat(ItemViews.canSee(processing, VISITOR)).isFalse();
        assertThat(ItemViews.canSee(trashed, MEMBER)).isFalse();
        assertThat(ItemViews.canSee(trashed, EDITOR)).isTrue();
        assertThat(ItemViews.canSee(contribution, MEMBER)).isTrue();
        assertThat(ItemViews.canSee(contribution, new Viewer(Viewer.Kind.USER, TestCallers.admin()))).isFalse();
    }

    @Test
    void aSuggestedAlbumIsSeenByItsSuggesterWhileItWaits() {
        final Album album = album(Visibility.UNPUBLISHED);

        album.setSuggestedBy("s-viewer");
        assertThat(ItemViews.canSee(album, MEMBER)).isTrue();
        assertThat(ItemViews.canSee(album, VISITOR)).isFalse();
    }
}
