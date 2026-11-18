-- Make-up (content-import.md, 2.1): the photos of each spex' make-up get an album kind of their own. Photos of the
-- technique (lighting, sound) are Övrigt. Övrigt stays last.
UPDATE album_kind SET position = 7, aliases = 'övrigt\nannat\nother\nporträtt\nspexarporträtt\ngruppbild\nteknik'
WHERE uuid = '00000000-0000-7000-8000-00000000c006';

INSERT INTO album_kind (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000c007', 'Sminkning', 'Make-up', 'sminkning\nsmink\nsminkningar', 6, 'system', NOW(6));
