-- An episode's video and audio (MediaFiles.kt): the full episode and its bonuses, each as video or audio only, uploaded
-- from its page to MetaOS's private bucket, at path. What's in each file is as ffprobe read it once it was there: its
-- whole output in probe, and what's shown and asked for in the columns from duration to encoder. One full video and one
-- full audio per episode; any number of bonuses. Removing one deletes its row and its file; the audit log keeps the row.
CREATE TABLE episode_media
(
    id            UUID PRIMARY KEY,
    episode_id    UUID        NOT NULL REFERENCES episodes (id),
    kind          TEXT        NOT NULL CHECK (kind IN ('FULL_VIDEO', 'FULL_AUDIO', 'BONUS_VIDEO', 'BONUS_AUDIO')),
    title         TEXT CHECK (btrim(title) <> ''),
    name          TEXT        NOT NULL, -- the file's name, as it was uploaded
    path          TEXT        NOT NULL UNIQUE,
    content_type  TEXT        NOT NULL,
    size          BIGINT      NOT NULL,
    md5           TEXT,                 -- hex, as Cloud Storage has it
    crc32c        TEXT,                 -- hex
    uploader_id   TEXT        NOT NULL, -- Keycloak user ID (sub)
    uploader      TEXT,                 -- username at the time
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration      DOUBLE PRECISION,     -- seconds
    bitrate       BIGINT,               -- bits a second, overall
    container     TEXT,                 -- e.g. MP4, MOV, MP3
    video_codec   TEXT,                 -- ffprobe's names, e.g. h264, hevc, prores
    video_profile TEXT,
    width         INT,
    height        INT,
    frame_rate    DOUBLE PRECISION,
    bit_depth     INT,
    color         TEXT,                 -- e.g. bt709, or HDR (PQ)
    audio_codec   TEXT,                 -- e.g. aac, mp3, pcm_s24le
    sample_rate   INT,
    channels      INT,
    audio_bitrate BIGINT,
    audio_tracks  INT         NOT NULL DEFAULT 0,
    made_at       TIMESTAMPTZ,          -- when the file says it was made (creation_time)
    encoder       TEXT,                 -- what made it, as the file says
    probe         JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX episode_media_episode_id_idx ON episode_media (episode_id, uploaded_at);
CREATE UNIQUE INDEX episode_media_one_full_idx ON episode_media (episode_id, kind) WHERE kind IN ('FULL_VIDEO', 'FULL_AUDIO');

CREATE TRIGGER episode_media_set_updated_at
    BEFORE UPDATE
    ON episode_media
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the history and the front-end's live updates (V22__change_notifications.sql)
CREATE TRIGGER episode_media_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON episode_media
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
