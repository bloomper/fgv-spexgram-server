-- The import API: which record each source key made, and what it imported ------------------------------------------

CREATE TABLE import_key
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    source_key     VARCHAR(1000) NOT NULL,
    -- SHA-256 of the source key, for the unique index (source keys may be long)
    source_hash    CHAR(64)      NOT NULL,
    record_kind    VARCHAR(30)   NOT NULL,
    record_id      CHAR(36)      NOT NULL,
    -- SHA-256 of the record as last imported, to tell UNCHANGED from UPDATED
    imported_hash  CHAR(64)      NOT NULL,
    -- The record's version right after the import; a different version later means an editor changed it (KEPT)
    record_version BIGINT        NULL,
    imported_by    VARCHAR(64)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_import_key_source UNIQUE (source_hash),
    INDEX idx_import_key_record (record_kind, record_id),
    INDEX idx_import_key_prefix (source_key(255))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
