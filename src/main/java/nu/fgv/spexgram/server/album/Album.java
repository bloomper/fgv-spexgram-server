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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexgram.server.util.AbstractAuditable;
import nu.fgv.spexgram.server.util.Uuids;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Something that was photographed: a show, a rehearsal, a party, a tour or a jubilee. It may be about spex in
 * Spexregister, the main one first. New albums start unpublished.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "album")
@Getter
@Setter
public class Album extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kind_id", nullable = false)
    private AlbumKind kind;

    /**
     * The spex the album is about, the main one first; empty when it is not about one spex.
     */
    @ElementCollection
    @CollectionTable(name = "album_spex", joinColumns = @JoinColumn(name = "album_id"))
    @OrderColumn(name = "position")
    private List<SpexRef> spex = new ArrayList<>();

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "date_from")
    private @Nullable LocalDate dateFrom;

    /**
     * The last day of a period, e.g. a tour; null for a single day.
     */
    @Column(name = "date_to")
    private @Nullable LocalDate dateTo;

    @Column(name = "place")
    private @Nullable String place;

    @Column(name = "description")
    private @Nullable String description;

    /**
     * The item shown for the album, by id; the first item when none is chosen.
     */
    @Column(name = "cover_item_id")
    private @Nullable Long coverItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private Visibility visibility = Visibility.UNPUBLISHED;

    /**
     * The member who suggested the album, while the suggestion waits for an editor.
     */
    @Column(name = "suggested_by")
    private @Nullable String suggestedBy;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    public @Nullable SpexRef mainSpex() {
        return spex.isEmpty() ? null : spex.getFirst();
    }

    public boolean isTrashed() {
        return trashedAt != null;
    }
}
