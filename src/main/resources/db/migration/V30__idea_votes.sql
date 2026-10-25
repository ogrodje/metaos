-- The team's votes on episode ideas, up or down, one per user and idea (Votes.kt). Whoever voted is kept by their
-- Keycloak user ID, as comments' authors are, and named through their account when shown. Taking a vote back deletes
-- it; the audit log keeps that it was there. Votes stay when the idea is planned, closed to changes (Votes.kt).
CREATE TABLE idea_votes
(
    id         UUID PRIMARY KEY,
    episode_id UUID        NOT NULL REFERENCES episodes (id),
    voter_id   TEXT        NOT NULL, -- Keycloak user ID (sub)
    voter      TEXT,                 -- username at the time
    value      SMALLINT    NOT NULL CHECK (value IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idea_votes_one_each UNIQUE (episode_id, voter_id)
);

CREATE TRIGGER idea_votes_set_updated_at
    BEFORE UPDATE
    ON idea_votes
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER idea_votes_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON idea_votes
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
