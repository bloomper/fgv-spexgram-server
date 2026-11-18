-- A member's favourites: personal, never changing what others see. They go with the member's record (36 months after
-- their last login) and with the item.
CREATE TABLE favourite
(
    user_id    BIGINT      NOT NULL,
    item_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, item_id),
    CONSTRAINT fk_favourite_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_favourite_item FOREIGN KEY (item_id) REFERENCES item (id) ON DELETE CASCADE,
    INDEX idx_favourite_item (item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
