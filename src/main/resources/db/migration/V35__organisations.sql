-- Organisations (Organisations.kt): Ogrodje's supporters and partners, listed with their logos on the home page and in
-- the footer while they support it, and the companies people work at; the start of the CRM. Their finance details
-- (VAT ID, company ID, address, who to contact) are the team's. Soft-deleted, as people are.
CREATE TABLE organisations
(
    id                 UUID PRIMARY KEY,
    name               CITEXT      NOT NULL CHECK (btrim(name) <> ''),
    description        TEXT CHECK (btrim(description) <> ''),
    -- How it supports Ogrodje, as ogrodje.si's footer had them (Podporniki, Partnerji), or not at all
    tier               TEXT        NOT NULL DEFAULT 'NONE' CHECK (tier IN ('SUPPORTER', 'PARTNER', 'NONE')),
    -- Days, both included; without a start it has supported Ogrodje since before MetaOS knew, without an end it still does
    support_started_at DATE,
    support_ended_at   DATE CHECK (support_ended_at >= support_started_at),
    website_url        TEXT CHECK (website_url ~ '^https?://'),
    linkedin_url       TEXT CHECK (linkedin_url ~ '^https://'),
    twitter_url        TEXT CHECK (twitter_url ~ '^https://'),
    instagram_url      TEXT CHECK (instagram_url ~ '^https://'),
    facebook_url       TEXT CHECK (facebook_url ~ '^https://'),
    -- In the public bucket (Images.kt)
    logo_image_id      UUID REFERENCES images (id),
    vat_id             TEXT CHECK (btrim(vat_id) <> ''),     -- e.g. SI12345678
    company_id         TEXT CHECK (btrim(company_id) <> ''), -- the registry's, e.g. a matična številka
    address            TEXT CHECK (btrim(address) <> ''),
    contact_person_id  UUID REFERENCES people (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ
);

-- Names are unique among live organisations only, so a soft-deleted one's name can be reused
CREATE UNIQUE INDEX organisations_name_key ON organisations (name) WHERE deleted_at IS NULL;

CREATE TRIGGER organisations_set_updated_at
    BEFORE UPDATE
    ON organisations
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Who works at an organisation, with their title and from when till when (days); several times, for one who came back.
-- Unlinking deletes it, and the audit log keeps it (restore_link).
CREATE TABLE person_organisations
(
    id              UUID PRIMARY KEY,
    person_id       UUID        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    organisation_id UUID        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    title           TEXT CHECK (btrim(title) <> ''), -- e.g. CTO
    started_at      DATE,
    ended_at        DATE CHECK (ended_at >= started_at),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX person_organisations_person_id_idx ON person_organisations (person_id);
CREATE INDEX person_organisations_organisation_id_idx ON person_organisations (organisation_id);

CREATE TRIGGER person_organisations_set_updated_at
    BEFORE UPDATE
    ON person_organisations
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- The organisations that supported an episode's production, as Hygraph's episodes had their supporters
CREATE TABLE episode_organisations
(
    id              UUID PRIMARY KEY,
    episode_id      UUID        NOT NULL REFERENCES episodes (id) ON DELETE CASCADE,
    organisation_id UUID        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT episode_organisations_key UNIQUE (episode_id, organisation_id)
);

CREATE INDEX episode_organisations_organisation_id_idx ON episode_organisations (organisation_id);

-- ogrodje.si's supporters and partners, as its footer listed them, before the audit triggers, so their logos are
-- published once (OrganisationLogos) and they're nobody's change. By fixed IDs, which OrganisationLogos knows them by.
INSERT INTO organisations (id, name, tier, website_url)
VALUES ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0001', 'Kaldi', 'SUPPORTER', 'https://kaldi.si'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0002', 'Visionect', 'SUPPORTER', 'https://www.visionect.com'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0003', 'Koofr', 'SUPPORTER', 'https://koofr.eu'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0004', 'NGEN Group', 'SUPPORTER', 'https://www.ngen-group.eu/'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0005', 'Monitor', 'PARTNER', 'https://www.monitor.si/'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0006', 'Spletne Urice Maribor', 'PARTNER', 'https://wwwhmb.si/'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0007', 'Računalniški muzej', 'PARTNER', 'https://www.racunalniski-muzej.si/'),
       ('6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0008', 'SLO AI', 'PARTNER', 'https://sloai.org/');

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER organisations_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON organisations
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

CREATE TRIGGER person_organisations_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON person_organisations
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

CREATE TRIGGER episode_organisations_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON episode_organisations
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
