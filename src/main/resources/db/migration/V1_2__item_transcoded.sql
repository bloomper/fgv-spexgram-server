-- Whether Immich has made the browser version of a video, which carries none of the original's metadata (location,
-- creation time); only then is it played to viewers who may not download originals
ALTER TABLE item
    ADD COLUMN transcoded BOOLEAN NOT NULL DEFAULT FALSE AFTER status;
