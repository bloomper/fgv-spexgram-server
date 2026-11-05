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


package nu.fgv.spexgram.server.contribution;

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
import nu.fgv.spexgram.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's suggestion of a new album. The album is created at once, unpublished, so that the member can contribute
 * to it; an editor accepts it, which publishes it for members, or rejects it, which deletes it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "album_suggestion")
@Getter
@Setter
public class AlbumSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id")
    private @Nullable Album album;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "suggested_by_subject", nullable = false)
    private String suggestedBySubject;

    @Column(name = "suggested_by_name")
    private @Nullable String suggestedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "message")
    private @Nullable String message;

    @Column(name = "handled_by")
    private @Nullable String handledBy;

    @Column(name = "handled_at")
    private @Nullable Instant handledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum Status {
        OPEN,
        ACCEPTED,
        REJECTED
    }
}
