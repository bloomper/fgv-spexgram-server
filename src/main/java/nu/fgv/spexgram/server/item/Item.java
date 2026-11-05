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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.util.AbstractAuditable;
import nu.fgv.spexgram.server.util.Uuids;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One photo or video. The file lives in Immich as the asset {@link #immichAssetId}; Spexgram keeps what Immich does
 * not know: the album, the caption, who may see it. An item without an album is unsorted (5.6 of the spec).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "item")
@Getter
@Setter
public class Item extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id")
    private @Nullable Album album;

    /**
     * The asset in Immich; null once a rejected file has been deleted there.
     */
    @Column(name = "immich_asset_id", length = 36)
    private @Nullable UUID immichAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ItemType type = ItemType.PHOTO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ItemStatus status = ItemStatus.PROCESSING;

    /**
     * Why processing failed or the file was rejected, for editors.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure")
    private @Nullable ItemFailure failure;

    /**
     * When it was taken, in local time, from the file's metadata; editable, since scans carry the scan date.
     */
    @Column(name = "taken_at")
    private @Nullable LocalDateTime takenAt;

    @Column(name = "caption")
    private @Nullable String caption;

    @Column(name = "photographer")
    private @Nullable String photographer;

    @Column(name = "latitude")
    private @Nullable Double latitude;

    @Column(name = "longitude")
    private @Nullable Double longitude;

    @Column(name = "width")
    private @Nullable Integer width;

    @Column(name = "height")
    private @Nullable Integer height;

    @Column(name = "duration_ms")
    private @Nullable Long durationMs;

    @Column(name = "file_size")
    private @Nullable Long fileSize;

    @Column(name = "original_file_name")
    private @Nullable String originalFileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private Visibility visibility = Visibility.MEMBERS;

    /**
     * Set for items a {@code USER} contributed: waiting for review, or accepted.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_status")
    private @Nullable ContributionStatus contributionStatus;

    @Column(name = "uploaded_by_subject")
    private @Nullable String uploadedBySubject;

    @Column(name = "uploaded_by_name")
    private @Nullable String uploadedByName;

    /**
     * When Immich got the file, to give up on processing that never finishes.
     */
    @Column(name = "processing_since")
    private @Nullable Instant processingSince;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    public boolean isTrashed() {
        return trashedAt != null;
    }

    public boolean isWaitingForReview() {
        return contributionStatus == ContributionStatus.WAITING;
    }

    /**
     * The visibility the item is seen with, its album's taken into account (4.1).
     */
    public Visibility effectiveVisibility() {
        return Visibility.effective(album == null ? null : album.getVisibility(), album != null && album.isTrashed(),
                visibility, isTrashed());
    }
}
