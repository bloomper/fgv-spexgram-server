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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexgram.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An editor's decision on one contribution, kept for the contributor's daily email and 24 months after.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "contribution_review")
@Getter
@Setter
public class ContributionReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    /**
     * The item, while it exists; a rejected one is deleted.
     */
    @Column(name = "item_id")
    private @Nullable Long itemId;

    @Column(name = "album_title")
    private @Nullable String albumTitle;

    @Column(name = "file_name")
    private @Nullable String fileName;

    @Column(name = "contributor_subject", nullable = false)
    private String contributorSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private Outcome outcome;

    @Column(name = "message")
    private @Nullable String message;

    @Column(name = "reviewer_subject", nullable = false)
    private String reviewerSubject;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "notified_at")
    private @Nullable Instant notifiedAt;

    public enum Outcome {
        ACCEPTED,
        REJECTED
    }
}
