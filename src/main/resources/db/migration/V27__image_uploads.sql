-- Pictures uploaded from the browser (Artwork.kt), e.g. an episode's artwork from Upload artwork on its page: kept by
-- the file's name (source_path), as the upload itself is deleted once it's published.
ALTER TABLE images
    DROP CONSTRAINT images_source_check,
    ADD CONSTRAINT images_source_check CHECK (source IN ('DROPBOX', 'KOOFR', 'URL', 'UPLOAD'));
