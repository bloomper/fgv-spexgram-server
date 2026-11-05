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

import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Items as the GraphQL API shows and takes them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ItemDtos {

    private ItemDtos() {
    }

    /**
     * An item as the viewer may see it: positions, uploader and file details are left out for those who may not see
     * them (see {@link ItemViews}).
     *
     * @param albumId the album, when the viewer may see it
     */
    public record ItemDto(UUID id, @Nullable UUID albumId, ItemType type, ItemStatus status, @Nullable ItemFailure failure,
                          @Nullable String takenAt, @Nullable String caption, @Nullable String photographer,
                          @Nullable Double latitude, @Nullable Double longitude, @Nullable Integer width,
                          @Nullable Integer height, @Nullable Long durationMs, Visibility visibility,
                          Visibility effectiveVisibility, @Nullable ContributionStatus contributionStatus, boolean mine,
                          @Nullable String uploadedBy, @Nullable String originalFileName, @Nullable Long fileSize,
                          @Nullable Instant trashedAt, Instant createdAt, @Nullable MediaDto media) {
    }

    /**
     * Signed addresses on the media server.
     */
    public record MediaDto(String thumbnailUrl, String previewUrl, @Nullable String videoUrl,
                           @Nullable String originalUrl, Instant expiresAt) {
    }

    public record ItemPage(List<ItemDto> items, long totalCount, int page, int size) {
    }

    public record ItemInput(@Nullable String caption, @Nullable String photographer, @Nullable String takenAt,
                            @Nullable Double latitude, @Nullable Double longitude) {
    }

    public record ItemsInput(@Nullable String caption, @Nullable String photographer, @Nullable Visibility visibility,
                             @Nullable UUID albumId) {
    }

    public enum UploadOutcome {
        CREATED,
        DUPLICATE,
        REJECTED
    }

    public record UploadResult(String uploadId, String fileName, UploadOutcome outcome, @Nullable ItemDto item,
                               @Nullable ItemDto existingItem, @Nullable String existingAlbumTitle,
                               @Nullable String message) {
    }

    public record UnsortedDay(@Nullable String date, List<ItemDto> items) {
    }

    public record MapItem(UUID id, double latitude, double longitude, @Nullable String takenAt,
                          @Nullable String thumbnailUrl, @Nullable Instant expiresAt) {
    }

    public enum ImmichJob {
        REGENERATE_THUMBNAIL("regenerate-thumbnail"),
        REFRESH_METADATA("refresh-metadata"),
        TRANSCODE_VIDEO("transcode-video");

        private final String immichName;

        ImmichJob(final String immichName) {
            this.immichName = immichName;
        }

        public String immichName() {
            return immichName;
        }
    }
}
