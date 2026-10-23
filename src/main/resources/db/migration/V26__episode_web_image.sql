-- An episode's web picture: its thumbnail without the words, for ogrodje.si. 1920 × 1080, as its thumbnails are
-- (EpisodeImage in Images.kt says each picture's size).
ALTER TABLE episodes
    ADD COLUMN web_image_id UUID REFERENCES images (id);
