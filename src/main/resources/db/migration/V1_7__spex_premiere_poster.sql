-- The premiere and poster of each spex copied onto albums, from Spexipedia: for the memories of spex whose premiere is
-- near today's date, and for the spex tiles on the explore page
ALTER TABLE album_spex
    ADD COLUMN premiere   DATE          NULL AFTER category_name,
    ADD COLUMN poster_url VARCHAR(1000) NULL AFTER premiere;
