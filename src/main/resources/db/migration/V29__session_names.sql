-- A recording session's name, e.g. AWS Community Day Adria 2026, apart from where it is (location, the venue, and
-- address). Until now the location named a session, so that's what each one is called to begin with. Without a name,
-- a session goes by its location, else by its date. (V28 is another branch's.)
ALTER TABLE sessions
    ADD COLUMN name TEXT;

-- Nobody renamed them, so it's not in their history (audit_log, and so the live updates) and leaves updated_at be
ALTER TABLE sessions
    DISABLE TRIGGER sessions_audit,
    DISABLE TRIGGER sessions_set_updated_at;

UPDATE sessions
SET name = location
WHERE location IS NOT NULL;

ALTER TABLE sessions
    ENABLE TRIGGER sessions_audit,
    ENABLE TRIGGER sessions_set_updated_at;
