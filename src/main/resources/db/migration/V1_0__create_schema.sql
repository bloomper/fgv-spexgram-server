-- Spexgram's own data. The files, their thumbnails, previews and transcoded videos live in Immich, which knows nothing
-- of albums or who may see what; items refer to Immich's assets by id. Entities are known outside by uuid; the BIGINT
-- ids never leave the server.

CREATE TABLE app_user
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    subject      VARCHAR(64)  NOT NULL,
    username     VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NULL,
    email        VARCHAR(255) NULL,
    -- Whether their latest token had EDITOR or ADMIN; candidates for editors' notifications, checked in Keycloak
    editor       BIT          NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_subject UNIQUE (subject),
    INDEX idx_app_user_last_seen (last_seen_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Album kinds are configured by admins. The first by position is the default for new albums.
CREATE TABLE album_kind
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name_sv          VARCHAR(100) NOT NULL,
    name_en          VARCHAR(100) NOT NULL,
    position         INT          NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_kind_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO album_kind (uuid, name_sv, name_en, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000c001', 'Föreställning', 'Show', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c002', 'Repetition', 'Rehearsal', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c003', 'Fest', 'Party', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c004', 'Turné', 'Tour', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c005', 'Jubileum', 'Jubilee', 5, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c006', 'Övrigt', 'Other', 6, 'system', NOW(6));

CREATE TABLE album
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    kind_id          BIGINT       NOT NULL,
    title            VARCHAR(255) NOT NULL,
    date_from        DATE         NULL,
    date_to          DATE         NULL,
    place            VARCHAR(255) NULL,
    description      TEXT         NULL,
    cover_item_id    BIGINT       NULL,
    visibility       VARCHAR(20)  NOT NULL,
    -- The member who suggested it, while the suggestion waits: they see it and contribute to it before it is published
    suggested_by     VARCHAR(64)  NULL,
    trashed_at       DATETIME(6)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_uuid UNIQUE (uuid),
    CONSTRAINT fk_album_kind FOREIGN KEY (kind_id) REFERENCES album_kind (id),
    INDEX idx_album_date (date_from),
    FULLTEXT INDEX ft_album (title, place, description)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The spex an album is about, the main one first; empty when it is not about one spex. Each keeps the id of its
-- original when it is a revival, so that revivals are shown with their original.
CREATE TABLE album_spex
(
    album_id      BIGINT       NOT NULL,
    position      INT          NOT NULL,
    spex_id       CHAR(36)     NOT NULL,
    parent_id     CHAR(36)     NULL,
    title         VARCHAR(255) NULL,
    year          SMALLINT     NULL,
    revival       BIT          NULL,
    category_id   CHAR(36)     NULL,
    category_name VARCHAR(255) NULL,
    refreshed_at  DATETIME(6)  NULL,
    PRIMARY KEY (album_id, position),
    CONSTRAINT fk_album_spex_album FOREIGN KEY (album_id) REFERENCES album (id) ON DELETE CASCADE,
    INDEX idx_album_spex_spex (spex_id),
    INDEX idx_album_spex_parent (parent_id),
    INDEX idx_album_spex_category (category_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A photo or video. Without an album it is unsorted: found in Immich, e.g. after a bulk import.
CREATE TABLE item
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    uuid                CHAR(36)      NOT NULL,
    album_id            BIGINT        NULL,
    immich_asset_id     CHAR(36)      NULL,
    type                VARCHAR(10)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    failure             VARCHAR(20)   NULL,
    taken_at            DATETIME(6)   NULL,
    caption             TEXT          NULL,
    photographer        VARCHAR(255)  NULL,
    latitude            DOUBLE        NULL,
    longitude           DOUBLE        NULL,
    width               INT           NULL,
    height              INT           NULL,
    duration_ms         BIGINT        NULL,
    file_size           BIGINT        NULL,
    original_file_name  VARCHAR(255)  NULL,
    visibility          VARCHAR(20)   NOT NULL,
    contribution_status VARCHAR(20)   NULL,
    uploaded_by_subject VARCHAR(64)   NULL,
    uploaded_by_name    VARCHAR(255)  NULL,
    processing_since    DATETIME(6)   NULL,
    trashed_at          DATETIME(6)   NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_by          VARCHAR(255)  NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    last_modified_by    VARCHAR(255)  NULL,
    last_modified_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_item_uuid UNIQUE (uuid),
    CONSTRAINT uk_item_asset UNIQUE (immich_asset_id),
    CONSTRAINT fk_item_album FOREIGN KEY (album_id) REFERENCES album (id),
    INDEX idx_item_album_taken (album_id, taken_at),
    INDEX idx_item_status (status),
    INDEX idx_item_taken (taken_at),
    INDEX idx_item_uploader (uploaded_by_subject, created_at),
    FULLTEXT INDEX ft_item (caption, photographer)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE album
    ADD CONSTRAINT fk_album_cover FOREIGN KEY (cover_item_id) REFERENCES item (id) ON DELETE SET NULL;

-- An editor's decision on a contribution, kept for the contributor's daily email and 24 months after
CREATE TABLE contribution_review
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    uuid                CHAR(36)      NOT NULL,
    item_id             BIGINT        NULL,
    album_title         VARCHAR(255)  NULL,
    file_name           VARCHAR(255)  NULL,
    contributor_subject VARCHAR(64)   NOT NULL,
    outcome             VARCHAR(20)   NOT NULL,
    message             VARCHAR(2000) NULL,
    reviewer_subject    VARCHAR(64)   NOT NULL,
    reviewed_at         DATETIME(6)   NOT NULL,
    notified_at         DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_contribution_review_uuid UNIQUE (uuid),
    CONSTRAINT fk_contribution_review_item FOREIGN KEY (item_id) REFERENCES item (id) ON DELETE SET NULL,
    INDEX idx_contribution_review_notify (notified_at, contributor_subject),
    INDEX idx_contribution_review_reviewed (reviewed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A user's suggestion of a new album, which they contribute to while it waits
CREATE TABLE album_suggestion
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    uuid                 CHAR(36)      NOT NULL,
    album_id             BIGINT        NULL,
    title                VARCHAR(255)  NOT NULL,
    suggested_by_subject VARCHAR(64)   NOT NULL,
    suggested_by_name    VARCHAR(255)  NULL,
    status               VARCHAR(20)   NOT NULL,
    message              VARCHAR(2000) NULL,
    handled_by           VARCHAR(64)   NULL,
    handled_at           DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_suggestion_uuid UNIQUE (uuid),
    CONSTRAINT fk_album_suggestion_album FOREIGN KEY (album_id) REFERENCES album (id) ON DELETE SET NULL,
    INDEX idx_album_suggestion_status (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A request to remove or correct an item, by a user or a visitor (with an email address for the answer)
CREATE TABLE report
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    item_id          BIGINT        NULL,
    item_caption     VARCHAR(255)  NULL,
    reporter_subject VARCHAR(64)   NULL,
    reporter_name    VARCHAR(255)  NULL,
    visitor_email    VARCHAR(255)  NULL,
    locale           VARCHAR(10)   NOT NULL,
    reason           VARCHAR(20)   NOT NULL,
    message          VARCHAR(2000) NULL,
    status           VARCHAR(20)   NOT NULL,
    -- The item's visibility before the report hid it, restored when it is kept or corrected
    hidden_from      VARCHAR(20)   NULL,
    outcome          VARCHAR(20)   NULL,
    outcome_message  VARCHAR(2000) NULL,
    handled_by       VARCHAR(64)   NULL,
    handled_at       DATETIME(6)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_report_uuid UNIQUE (uuid),
    CONSTRAINT fk_report_item FOREIGN KEY (item_id) REFERENCES item (id) ON DELETE SET NULL,
    INDEX idx_report_status (status, created_at),
    INDEX idx_report_reporter (reporter_subject)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settings
(
    id                       BIGINT       NOT NULL,
    welcome_sv               TEXT         NULL,
    welcome_en               TEXT         NULL,
    public_welcome_sv        TEXT         NULL,
    public_welcome_en        TEXT         NULL,
    contact_email            VARCHAR(255) NULL,
    max_upload_gb            INT          NOT NULL,
    max_video_minutes        INT          NOT NULL,
    contribution_max_items   INT          NOT NULL,
    contribution_max_gb      INT          NOT NULL,
    user_downloads_originals BIT          NOT NULL,
    show_positions           BIT          NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    last_modified_by         VARCHAR(255) NULL,
    last_modified_at         DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO settings (id, max_upload_gb, max_video_minutes, contribution_max_items, contribution_max_gb,
                      user_downloads_originals, show_positions)
VALUES (1, 4, 10, 500, 20, 0, 1);

CREATE TABLE audit_entry
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    at            DATETIME(6)   NOT NULL,
    actor_subject VARCHAR(64)   NOT NULL,
    actor_name    VARCHAR(255)  NULL,
    action        VARCHAR(40)   NOT NULL,
    entity_type   VARCHAR(30)   NOT NULL,
    entity_id     CHAR(36)      NULL,
    summary       VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_entry_at (at),
    INDEX idx_audit_entry_actor (actor_subject, at),
    INDEX idx_audit_entry_entity (entity_id, at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_session
(
    id            CHAR(36)     NOT NULL,
    owner         VARCHAR(64)  NOT NULL,
    length        BIGINT       NOT NULL,
    upload_offset BIGINT       NOT NULL,
    file_name     VARCHAR(255) NULL,
    last_modified DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    completed_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_upload_session_created (created_at),
    INDEX idx_upload_session_owner (owner, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
