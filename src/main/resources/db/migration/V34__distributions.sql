-- Where an episode went out once it was released (Distributions.kt): its own links (platform, one row each, kept as
-- the episode's link columns are) and the posts that promote it, e.g. on Discord, LinkedIn or Reddit, any number. Each
-- with a preview from its page's OpenGraph tags, as a workflow fetched it (DistributionPreview.kt); title and note are
-- what someone gave it, and outlast the preview. Soft-deleted, as comments are.
CREATE TABLE distributions
(
    id                   UUID PRIMARY KEY,
    episode_id           UUID        NOT NULL REFERENCES episodes (id),
    platform             TEXT,                 -- the episode's link this is (Platform), e.g. YOUTUBE; null for a post
    channel              TEXT        NOT NULL, -- where it is (Channel), e.g. LINKEDIN
    url                  TEXT        NOT NULL CHECK (url ~ '^https?://'), -- https, as Distributions.kt takes them
    title                TEXT CHECK (btrim(title) <> ''),
    note                 TEXT CHECK (btrim(note) <> '' AND length(note) <= 2000),
    posted_at            TIMESTAMPTZ,          -- when it went out, as someone said
    added_by_id          TEXT,                 -- Keycloak user ID (sub)
    added_by             TEXT,                 -- username at the time
    preview_status       TEXT        NOT NULL DEFAULT 'PENDING' CHECK (preview_status IN ('PENDING', 'OK', 'UNAVAILABLE')),
    preview_title        TEXT,
    preview_description  TEXT,
    preview_site         TEXT,
    preview_published_at TIMESTAMPTZ,          -- article:published_time, where the page says
    preview_image_id     UUID REFERENCES images (id),
    preview_error        TEXT,                 -- why there's no preview
    previewed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX distributions_episode_id_idx ON distributions (episode_id, created_at) WHERE deleted_at IS NULL;
-- A post once per episode; two of its links may be one URL, e.g. a Patreon post with its video and audio
CREATE UNIQUE INDEX distributions_url_idx ON distributions (episode_id, url) WHERE platform IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX distributions_platform_idx ON distributions (episode_id, platform)
    WHERE platform IS NOT NULL AND deleted_at IS NULL;

-- The links episodes have, before the audit trigger, as they're in the history already
INSERT INTO distributions (id, episode_id, platform, channel, url, created_at, updated_at)
SELECT gen_random_uuid(), e.id, link.platform, link.channel, link.url, now(), now()
FROM episodes e
         CROSS JOIN LATERAL (VALUES ('YOUTUBE', 'YOUTUBE', e.youtube_url),
                                    ('SPOTIFY', 'SPOTIFY', e.spotify_url),
                                    ('APPLE_PODCASTS', 'APPLE_PODCASTS', e.apple_podcasts_url),
                                    ('PATREON', 'PATREON', e.patreon_url),
                                    ('PATREON_AUDIO', 'PATREON', e.patreon_audio_url),
                                    ('PATREON_BONUS', 'PATREON', e.patreon_bonus_url),
                                    ('PATREON_BONUS_AUDIO', 'PATREON', e.patreon_bonus_audio_url)) AS link (platform, channel, url)
-- Deleted episodes' too, as they may be restored
WHERE link.url LIKE 'https://%';

CREATE TRIGGER distributions_set_updated_at
    BEFORE UPDATE
    ON distributions
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER distributions_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON distributions
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
