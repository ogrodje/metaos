-- Events (Events.kt): Slovenia's tech, developer and startup events, as Goo (goo.ogrodje.si) gathered them, now
-- MetaOS's own (OG-64). Goo's meetups are organisations: where each announces its events is in its links, which the
-- scrapers read (scrapers/, EventsSync.kt), and Goo's colours, the badge's on the Events page.
ALTER TABLE organisations
    ADD COLUMN meetup_url       TEXT CHECK (meetup_url ~ '^https?://'),
    ADD COLUMN eventbrite_url   TEXT CHECK (eventbrite_url ~ '^https?://'),
    ADD COLUMN ical_url         TEXT CHECK (ical_url ~ '^(https?|webcal)://'),
    ADD COLUMN luma_url         TEXT CHECK (luma_url ~ '^https?://'),
    ADD COLUMN discord_url      TEXT CHECK (discord_url ~ '^https?://'),
    ADD COLUMN kompot_url       TEXT CHECK (kompot_url ~ '^https?://'),
    -- The badge's, on the Events page: its text's and its background's, e.g. #ffffff
    ADD COLUMN main_color       TEXT CHECK (main_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD COLUMN background_color TEXT CHECK (background_color ~ '^#[0-9a-fA-F]{6}$'),
    -- Whether its events are gathered from its links; off, what's there stays. Not by default: V35's supporters and
    -- partners, and the companies people work at, have only their websites, where they don't announce events; Goo's
    -- meetups gather theirs (GooImport.kt), as does one made with an event link (Organisations.create)
    ADD COLUMN collects_events  BOOLEAN NOT NULL DEFAULT false,
    -- Archived: listed apart, and its events no longer gathered; nothing of it is deleted
    ADD COLUMN archived_at      TIMESTAMPTZ,
    -- The meetup in Hygraph it was imported from (GooImport.kt), so importing it again changes it rather than add it
    ADD COLUMN goo_id           TEXT UNIQUE;

-- An event, by the organisation it's by. One a scraper found has its source (which parser) and its ID there
-- (external_id, Goo's, e.g. meetup-123), so finding it again changes it; the team's own are MANUAL, without one.
-- Never deleted: the team hides one (hidden_at), and promotes Ogrodje's picks (promoted_at).
CREATE TABLE events
(
    id               UUID PRIMARY KEY,
    organisation_id  UUID        NOT NULL REFERENCES organisations (id),
    source           TEXT        NOT NULL,
    external_id      TEXT,
    -- The page the scraper read it from
    source_url       TEXT,
    title            TEXT        NOT NULL CHECK (btrim(title) <> ''),
    description      TEXT,
    -- Where it's announced
    url              TEXT CHECK (url ~ '^https?://'),
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ CHECK (ends_at >= starts_at),
    -- Whether its start and end have a time of day, or are days only
    has_start_time   BOOLEAN     NOT NULL DEFAULT true,
    has_end_time     BOOLEAN     NOT NULL DEFAULT true,
    location_name    TEXT,
    location_address TEXT,
    hidden_at        TIMESTAMPTZ,
    promoted_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT events_external_key UNIQUE (organisation_id, external_id)
);

CREATE INDEX events_starts_at_idx ON events (starts_at);
CREATE INDEX events_organisation_id_idx ON events (organisation_id);

CREATE TRIGGER events_set_updated_at
    BEFORE UPDATE
    ON events
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql). The
-- scrapers write only what changed, so a sync that finds nothing new writes nothing.
CREATE TRIGGER events_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON events
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

-- How each of an organisation's links was last scraped: when, by which parser, how many events it found, or why it
-- failed. Bookkeeping, every hour, so without an audit trigger.
CREATE TABLE event_scrapes
(
    organisation_id UUID        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    field           TEXT        NOT NULL,
    url             TEXT        NOT NULL,
    source          TEXT,
    scraped_at      TIMESTAMPTZ NOT NULL,
    events          INT         NOT NULL DEFAULT 0,
    error           TEXT,
    PRIMARY KEY (organisation_id, field)
);
