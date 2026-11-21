-- A change from Spexipedia's feed that keeps failing is tried a few times, then recorded here and left out, so that it
-- cannot stop the changes after it. Admins see the list.
ALTER TABLE spexipedia_sync
    ADD COLUMN failing_sequence VARCHAR(64) NULL,
    ADD COLUMN failing_attempts INT         NOT NULL DEFAULT 0;

CREATE TABLE spexipedia_sync_skipped
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    sequence    VARCHAR(64)   NOT NULL,
    kind        VARCHAR(40)   NOT NULL,
    record_id   CHAR(36)      NOT NULL,
    change_type VARCHAR(20)   NOT NULL,
    error       VARCHAR(1000) NOT NULL,
    skipped_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
