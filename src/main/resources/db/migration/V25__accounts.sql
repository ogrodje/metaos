-- The Keycloak users MetaOS has seen, by their ID (the token's sub), and the person each one is (Accounts.kt). A person
-- may have several, e.g. one that signs in with a password and one with Google; Google and Discord linked to the same
-- Keycloak user are one. An account is linked by hand, or by its verified email when it signs in or a person gets that
-- email (set_person_email), unless it was unlinked; it stays linked when emails change. Comments and the audit log keep
-- the sub, and are named through it when they're shown.
CREATE TABLE accounts
(
    id             UUID PRIMARY KEY,
    subject        TEXT        NOT NULL UNIQUE, -- Keycloak user ID (sub)
    username       TEXT,                        -- as the token says them, the last time they changed
    name           TEXT,
    email          CITEXT,
    email_verified BOOLEAN     NOT NULL DEFAULT false,
    person_id      UUID REFERENCES people (id),
    linked_how     TEXT CHECK (linked_how IN ('EMAIL', 'MANUAL')),
    linked_by      TEXT,                        -- who linked it, by sub; null when its email did
    linked_at      TIMESTAMPTZ,
    email_links    BOOLEAN     NOT NULL DEFAULT true, -- whether its verified email may link it; not once it's unlinked
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_linked CHECK ((person_id IS NULL) = (linked_how IS NULL)
        AND (person_id IS NULL) = (linked_at IS NULL))
);

CREATE INDEX accounts_person_id_idx ON accounts (person_id);

-- Everyone who has changed something already, with their latest username, so their comments and history are named
-- once they're linked. Before the audit trigger, as these aren't anyone's changes.
INSERT INTO accounts (id, subject, username)
SELECT DISTINCT ON (actor_id) gen_random_uuid(), actor_id, actor
FROM audit_log
WHERE actor_id IS NOT NULL
ORDER BY actor_id, id DESC;

CREATE TRIGGER accounts_set_updated_at
    BEFORE UPDATE
    ON accounts
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER accounts_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON accounts
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

-- Comments name their author through their account from now on, so linking one later names what it wrote before
ALTER TABLE comments
    DROP COLUMN person_id;
