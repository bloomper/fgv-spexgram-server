-- Other spellings of album kinds that the import recognises (Spexipedia's docs/content-import.md, 3), with defaults
-- matching the photo folders of FGV's file archive
ALTER TABLE album_kind
    ADD COLUMN aliases VARCHAR(2000) NOT NULL DEFAULT '' AFTER name_en;

UPDATE album_kind SET aliases = 'föreställning\nshow\nföreställningsbilder\nscenbilder\npremiär\nbilder\nprimär bilder' WHERE uuid = '00000000-0000-7000-8000-00000000c001';
UPDATE album_kind SET aliases = 'repetition\nrep\nrephelg\ngenrep\nreptider' WHERE uuid = '00000000-0000-7000-8000-00000000c002';
UPDATE album_kind SET aliases = 'fest\nparty\nvalborg\nfredagskalas\nsittning\nkalas' WHERE uuid = '00000000-0000-7000-8000-00000000c003';
UPDATE album_kind SET aliases = 'turné\nturne\ntour\nturnéer' WHERE uuid = '00000000-0000-7000-8000-00000000c004';
UPDATE album_kind SET aliases = 'jubileum\njubilee\njubileumsföreställning' WHERE uuid = '00000000-0000-7000-8000-00000000c005';
UPDATE album_kind SET aliases = 'övrigt\nannat\nother\nporträtt\nspexarporträtt\ngruppbild' WHERE uuid = '00000000-0000-7000-8000-00000000c006';
