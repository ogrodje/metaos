-- Transcripts of an episode's video and audio (Transcripts.kt), made by the transcription workflow (Transcription.kt)
-- with Google's Speech-to-Text. What was said is in MetaOS's private bucket, at path (JSON: the speakers and what each
-- said when), as a transcript of an episode is a few hundred kilobytes, which the audit log would keep again with
-- every change; here is what it's of, in which language, how it was made and who the speakers are. A file has a
-- transcript in the language spoken in it (spoken_language = language), which transcribing it again replaces, and may
-- have one in each other language (a translation; none yet). The episode and the file are linked by ID, as comments
-- are: the file may be removed, its transcripts stay.
CREATE TABLE transcripts
(
    id              UUID PRIMARY KEY,
    episode_id      UUID        NOT NULL REFERENCES episodes (id),
    media_id        UUID        NOT NULL,
    media_name      TEXT        NOT NULL, -- the file's name, e.g. S05E08.mp3
    path            TEXT        NOT NULL UNIQUE,
    language        TEXT        NOT NULL, -- what the text is in, BCP-47, e.g. sl-SI
    spoken_language TEXT        NOT NULL, -- what's said in the file, which Speech-to-Text heard
    model           TEXT        NOT NULL, -- Speech-to-Text's, e.g. chirp_3
    duration        DOUBLE PRECISION,     -- seconds of audio, as Speech-to-Text billed them
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
CREATE UNIQUE INDEX transcripts_media_language_idx ON transcripts (media_id, language);

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
