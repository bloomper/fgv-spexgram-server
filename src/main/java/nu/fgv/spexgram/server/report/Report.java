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


package nu.fgv.spexgram.server.report;

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
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.util.Uuids;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to remove or correct an item, by a member or by a visitor with an email address for the answer. Kept 24
 * months after it is handled.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "report")
@Getter
@Setter
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private @Nullable Item item;

    /**
     * What the item was called, for the reporter's answer after it is deleted.
     */
    @Column(name = "item_caption")
    private @Nullable String itemCaption;

    @Column(name = "reporter_subject")
    private @Nullable String reporterSubject;

    @Column(name = "reporter_name")
    private @Nullable String reporterName;

    /**
     * A visitor's address, kept with the report only.
     */
    @Column(name = "visitor_email")
    private @Nullable String visitorEmail;

    /**
     * The language the reporter wrote in, for the answer.
     */
    @Column(name = "locale", nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private Reason reason;

    @Column(name = "message")
    private @Nullable String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    /**
     * The item's own visibility before the report hid it, restored when it is kept or corrected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "hidden_from")
    private @Nullable Visibility hiddenFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private @Nullable Outcome outcome;

    @Column(name = "outcome_message")
    private @Nullable String outcomeMessage;

    @Column(name = "handled_by")
    private @Nullable String handledBy;

    @Column(name = "handled_at")
    private @Nullable Instant handledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum Reason {
        I_AM_IN_IT,
        WRONG_CAPTION_OR_ALBUM,
        INAPPROPRIATE,
        OTHER
    }

    public enum Status {
        OPEN,
        HANDLED
    }

    public enum Outcome {
        REMOVED,
        CORRECTED,
        KEPT
    }
}
