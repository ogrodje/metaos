-- Comments on episodes and recording sessions, as on GitHub: Markdown, in one thread each, by whoever was signed in,
-- through MetaOS's pages or MCP (Comments.kt). Only the author edits or deletes one; deleting is soft. Episodes, sessions
-- and people are linked by ID, so a comment stays when they're deleted, and comes back with them.
CREATE TABLE comments
(
    id         UUID PRIMARY KEY,
    episode_id UUID REFERENCES episodes (id),
    session_id UUID REFERENCES sessions (id),
    author_id  TEXT        NOT NULL, -- Keycloak user ID (sub)
    author     TEXT,                 -- username at the time
    person_id  UUID REFERENCES people (id) ON DELETE SET NULL, -- linked to the author by verified email at the time
    body       TEXT        NOT NULL CHECK (btrim(body) <> '' AND length(body) <= 20000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at  TIMESTAMPTZ,          -- when its text last changed
    deleted_at TIMESTAMPTZ,
    CONSTRAINT comments_on_one CHECK (num_nonnulls(episode_id, session_id) = 1)
);

CREATE INDEX comments_episode_id_idx ON comments (episode_id, created_at) WHERE episode_id IS NOT NULL;
CREATE INDEX comments_session_id_idx ON comments (session_id, created_at) WHERE session_id IS NOT NULL;

CREATE TRIGGER comments_set_updated_at
    BEFORE UPDATE
    ON comments
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER comments_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON comments
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
