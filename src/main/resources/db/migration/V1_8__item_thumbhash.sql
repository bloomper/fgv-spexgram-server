-- Immich's thumbhash of each item: a few bytes the web turns into a blurred placeholder until the thumbnail loads
ALTER TABLE item
    ADD COLUMN thumbhash VARCHAR(100) NULL AFTER transcoded;
