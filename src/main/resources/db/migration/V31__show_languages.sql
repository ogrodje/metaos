-- What a show's episodes are spoken in, as a BCP-47 tag, e.g. sl-SI or en-GB: what they're transcribed as
-- (Transcription.kt). Slovenian, but for Borderless, Ogrodje's show in English, with guests from abroad, which the team
-- transcribed as British English before. An episode without a show is in Slovenian too.
ALTER TABLE shows
    ADD COLUMN language TEXT NOT NULL DEFAULT 'sl-SI' CHECK (language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

UPDATE shows
SET language = 'en-GB'
WHERE name = 'Borderless';
