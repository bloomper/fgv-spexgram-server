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

import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.MediaDto;
import nu.fgv.spexgram.server.media.MediaSigner;
import nu.fgv.spexgram.server.settings.Settings;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Shows items as a viewer may see them: whether they may see an item or album at all, the same rule as
 * {@link ItemSpecifications}, and what of it, with signed media addresses. Visitors never get the uploader, a position
 * or an original; members get positions unless admins hide them, and originals when admins allow it; Spexipedia gets
 * thumbnails and previews only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ItemViews {

    static final DateTimeFormatter TAKEN = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MediaSigner signer;
    private final SettingsService settingsService;

    public ItemViews(final MediaSigner signer, final SettingsService settingsService) {
        this.signer = signer;
        this.settingsService = settingsService;
    }

    /**
     * Whether the viewer may open the album: editors any, also in the trash.
     */
    public static boolean canSee(final Album album, final Viewer viewer) {
        if (viewer.isEditor()) {
            return true;
        }
        if (album.isTrashed()) {
            return false;
        }
        if (viewer.kind() == Viewer.Kind.VISITOR) {
            return album.getVisibility() == Visibility.PUBLIC;
        }
        return album.getVisibility() != Visibility.UNPUBLISHED
                || (viewer.subject() != null && viewer.subject().equals(album.getSuggestedBy()));
    }

    /**
     * Whether the viewer may open the item: editors any, also unsorted and trashed ones.
     */
    public static boolean canSee(final Item item, final Viewer viewer) {
        if (viewer.isEditor()) {
            return true;
        }

        final Album album = item.getAlbum();

        if (album == null || album.isTrashed() || item.isTrashed()) {
            return false;
        }
        if (isOwnWaitingContribution(item, viewer)) {
            return true;
        }
        return item.getStatus() == ItemStatus.READY && viewer.sees(item.effectiveVisibility());
    }

    private static boolean isOwnWaitingContribution(final Item item, final Viewer viewer) {
        return item.isWaitingForReview() && viewer.subject() != null && viewer.subject().equals(item.getUploadedBySubject());
    }

    /**
     * Whether the viewer may download originals, videos and albums as zips.
     */
    public static boolean canDownloadOriginals(final Viewer viewer, final Settings settings) {
        return viewer.isEditor() || (viewer.kind() == Viewer.Kind.USER && settings.isUserDownloadsOriginals());
    }

    /**
     * What the viewer may see of items, for showing many: the settings and the expiry of the addresses, read once.
     */
    public Context context(final Viewer viewer) {
        final Settings settings = settingsService.current();

        return new Context(viewer, canDownloadOriginals(viewer, settings),
                viewer.isEditor() || (viewer.kind() == Viewer.Kind.USER && settings.isShowPositions()),
                signer.expiry());
    }

    public ItemDto toDto(final Item item, final Viewer viewer) {
        return toDto(item, context(viewer));
    }

    public ItemDto toDto(final Item item, final Context context) {
        final Viewer viewer = context.viewer();
        final boolean editor = viewer.isEditor();
        final boolean mine = viewer.subject() != null && viewer.subject().equals(item.getUploadedBySubject());
        final Album album = item.getAlbum();

        return new ItemDto(item.getUuid(),
                album != null && canSee(album, viewer) ? album.getUuid() : null,
                item.getType(),
                item.getStatus(),
                editor ? item.getFailure() : null,
                item.getTakenAt() == null ? null : TAKEN.format(item.getTakenAt()),
                item.getCaption(),
                item.getPhotographer(),
                context.positions() ? item.getLatitude() : null,
                context.positions() ? item.getLongitude() : null,
                item.getWidth(),
                item.getHeight(),
                item.getDurationMs(),
                item.getVisibility(),
                item.effectiveVisibility(),
                editor || mine ? item.getContributionStatus() : null,
                mine,
                editor ? item.getUploadedByName() : null,
                editor ? item.getOriginalFileName() : null,
                editor ? item.getFileSize() : null,
                editor ? item.getTrashedAt() : null,
                Objects.requireNonNull(item.getCreatedAt()),
                media(item, context));
    }

    private @Nullable MediaDto media(final Item item, final Context context) {
        final UUID asset = item.getImmichAssetId();

        if (asset == null) {
            return null;
        }

        final Instant expires = context.expires();
        final boolean video = item.getType() == ItemType.VIDEO;
        // Immich plays the original when it has no browser version; that, with its location, only for those who may
        // download originals
        final boolean playable = context.viewer().kind() != Viewer.Kind.SPEXIPEDIA && (item.isTranscoded() || context.originals());

        return new MediaDto(signer.sign(asset, MediaSigner.Variant.THUMBNAIL, expires),
                signer.sign(asset, MediaSigner.Variant.PREVIEW, expires),
                video && playable ? signer.sign(asset, MediaSigner.Variant.VIDEO, expires) : null,
                context.originals() ? signer.sign(asset, MediaSigner.Variant.ORIGINAL, expires) : null,
                expires);
    }

    /**
     * @param originals whether originals are signed for the viewer
     * @param positions whether the viewer sees positions
     * @param expires   when the addresses expire
     */
    public record Context(Viewer viewer, boolean originals, boolean positions, Instant expires) {
    }
}
