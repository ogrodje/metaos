-- Transcriptions (Transcriptions.kt): each time one of an episode's files is to be transcribed by a provider, from when
-- it's asked for till it's done, failed or cancelled, as its workflow (Transcription.kt) goes: waiting for a turn at
-- the provider (QUEUED), starting it (STARTING), the provider hearing it (RECOGNIZING), then DONE, with the transcript
-- it made, FAILED, with why, or CANCELLED. Temporal runs it; this is how far it is, for MetaOS's pages and tools, which
-- see it change as it happens (live updates, through the audit trigger), and what was transcribed when, by whom.
CREATE TABLE transcriptions
(
    id            UUID PRIMARY KEY,
    episode_id    UUID        NOT NULL REFERENCES episodes (id),
    media_id      UUID        NOT NULL,
    media_name    TEXT        NOT NULL, -- the file's name, e.g. S05E08.mp3
    provider      TEXT        NOT NULL, -- google or soniox
    model         TEXT,                 -- the provider's, once it's started
    language      TEXT        NOT NULL, -- what's said in the file, BCP-47, e.g. sl-SI
    batch         BOOLEAN     NOT NULL DEFAULT false,
    status        TEXT        NOT NULL CHECK (status IN ('QUEUED', 'STARTING', 'RECOGNIZING', 'DONE', 'FAILED', 'CANCELLED')),
    percent       INT,                  -- how far the provider got, where it says (Google), by tens
    error         TEXT,                 -- why it failed
    transcript_id UUID,                 -- the transcript it made; not a reference, as it may be replaced or removed
    workflow_id   TEXT        NOT NULL, -- Temporal's, transcribe-<media ID>-<provider>
    requester_id  TEXT,                 -- Keycloak user ID (sub) of who asked for it
    requester     TEXT,                 -- their username at the time
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,          -- when the provider started on it
    finished_at   TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transcriptions_episode_id_idx ON transcriptions (episode_id, created_at);
CREATE INDEX transcriptions_created_at_idx ON transcriptions (created_at);
-- A file is transcribed by one provider once at a time, as its workflow's ID says too
CREATE UNIQUE INDEX transcriptions_on_idx ON transcriptions (media_id, provider)
    WHERE status IN ('QUEUED', 'STARTING', 'RECOGNIZING');

CREATE TRIGGER transcriptions_set_updated_at
    BEFORE UPDATE
    ON transcriptions
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the front-end's live updates (V22__change_notifications.sql); an episode's history
-- leaves them out, as its transcripts say what was transcribed (AuditLog.history)
CREATE TRIGGER transcriptions_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON transcriptions
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
