-- Transcripts of an episode's video and audio (Transcripts.kt), made by the transcription workflow (Transcription.kt)
-- with a speech-to-text provider: Google's Speech-to-Text or Soniox (others later). Here is what a transcript is
-- of, in which language, who heard it and how, and who the speakers are; what was said is in transcript_contents, as
-- JSONB in MetaOS's own format, whichever provider heard it, and in the bucket too: the same as transcript.json (path),
-- next to what the provider answered, as it answered it (raw_path), from which it can be made again.
--
-- A file has a transcript in the language spoken in it (spoken_language = language) by each provider, to compare them,
-- which transcribing it again by that provider replaces, and may have one in each other language (a translation; none
-- yet). The episode and the file are linked by ID, as comments are: the file may be removed, its transcripts stay.
CREATE TABLE transcripts
(
    id              UUID PRIMARY KEY,
    episode_id      UUID        NOT NULL REFERENCES episodes (id),
    media_id        UUID        NOT NULL,
    media_name      TEXT        NOT NULL, -- the file's name, e.g. S05E08.mp3
    language        TEXT        NOT NULL, -- what the text is in, BCP-47, e.g. sl-SI
    spoken_language TEXT        NOT NULL, -- what's said in the file, which the provider heard
    provider        TEXT        NOT NULL, -- who heard it: google or soniox
    model           TEXT        NOT NULL, -- the provider's, e.g. long
    path            TEXT        NOT NULL UNIQUE, -- the transcript in the bucket, as in transcript_contents
    raw_path        TEXT,                 -- what the provider answered, in the bucket
    duration        DOUBLE PRECISION,     -- seconds of audio, as the provider billed them
    segments        INT         NOT NULL, -- stretches of what was said
    words           INT         NOT NULL,
    -- Who's who: [{"label": "1", "name": "Oto Brglez", "personId": "..."}], as the agent named them, else the labels only
    speakers        JSONB       NOT NULL DEFAULT '[]',
    workflow_id     TEXT        NOT NULL, -- Temporal's, of the workflow that made it
    requester_id    TEXT,                 -- Keycloak user ID (sub) of who asked for it
    requester       TEXT,                 -- their username at the time
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transcripts_episode_id_idx ON transcripts (episode_id, created_at);
CREATE UNIQUE INDEX transcripts_media_language_idx ON transcripts (media_id, language, provider);

CREATE TRIGGER transcripts_set_updated_at
    BEFORE UPDATE
    ON transcripts
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER transcripts_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON transcripts
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

-- What was said, as TranscriptContent (Transcripts.kt): its stretches, each with its words and when they were said.
-- A transcript's own table, without an audit trigger: an episode's is up to a megabyte, which audit_log would keep
-- again, whole, with its insert and its delete; its transcripts row says it was made, and by whom. It never changes:
-- a file transcribed again has another transcript.
CREATE TABLE transcript_contents
(
    transcript_id UUID PRIMARY KEY REFERENCES transcripts (id) ON DELETE CASCADE,
    content       JSONB NOT NULL CHECK (jsonb_typeof(content -> 'segments') = 'array')
);
