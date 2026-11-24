-- YouTube (YouTube.kt): the videos on Ogrodje's channel, as MetaOS knows them, and the uploads MetaOS sends there.

-- The channel's videos (YouTubeVideos.kt), Shorts and streams too, as YouTube's API says they are: gathered from the
-- channel every hour, and each one MetaOS uploaded as it's there. One is its episode's when the episode's YouTube link,
-- or one of its posts on YouTube (distributions), is its address, when MetaOS uploaded it from one of the episode's
-- files (media_id), or when the team said so (episode_manual, which the sync leaves). One that's no longer on the
-- channel stays, as gone.
CREATE TABLE youtube_videos
(
    id             UUID PRIMARY KEY,
    video_id       TEXT        NOT NULL UNIQUE,  -- YouTube's, e.g. dQw4w9WgXcQ
    channel_id     TEXT        NOT NULL,
    title          TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    tags           TEXT[]      NOT NULL DEFAULT '{}',
    category_id    TEXT,                         -- YouTube's, e.g. 28, Science & Technology
    language       TEXT,                         -- what's spoken in it, as YouTube has it, e.g. sl
    privacy        TEXT        NOT NULL CHECK (privacy IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    upload_status  TEXT,                         -- YouTube's: uploaded, processed, failed, rejected, deleted
    publish_at     TIMESTAMPTZ,                  -- when a private one is scheduled to go public
    published_at   TIMESTAMPTZ,                  -- when it was uploaded, or went public
    duration       INT,                          -- seconds
    live           BOOLEAN     NOT NULL DEFAULT false, -- a stream, or the recording of one
    thumbnail_url  TEXT,
    episode_id     UUID REFERENCES episodes (id),
    episode_manual BOOLEAN     NOT NULL DEFAULT false,
    media_id       UUID,                         -- the episode's file MetaOS uploaded it from; not a reference, as it may be removed
    gone_at        TIMESTAMPTZ,                  -- when it was found to be off the channel
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX youtube_videos_episode_id_idx ON youtube_videos (episode_id);
CREATE INDEX youtube_videos_media_id_idx ON youtube_videos (media_id);
CREATE INDEX youtube_videos_published_at_idx ON youtube_videos (published_at);

CREATE TRIGGER youtube_videos_set_updated_at
    BEFORE UPDATE
    ON youtube_videos
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the live updates; the histories tell only what someone did (an upload, a change to its
-- details), not what the sync found on the channel (AuditLog.history)
CREATE TRIGGER youtube_videos_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON youtube_videos
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();

-- Uploads (YouTubeUploads.kt): each time one of an episode's video files is sent to YouTube, from when it's asked for
-- till it's there, as its workflow goes: waiting for its turn (QUEUED), being sent (UPLOADING, with how far it is),
-- YouTube processing it (PROCESSING), then DONE, FAILED, with why, or CANCELLED. Always as private: a person publishes.
CREATE TABLE youtube_uploads
(
    id           UUID PRIMARY KEY,
    episode_id   UUID        NOT NULL REFERENCES episodes (id),
    media_id     UUID        NOT NULL,
    media_name   TEXT        NOT NULL, -- the file's name, e.g. S05E08.mp4
    title        TEXT        NOT NULL,
    description  TEXT        NOT NULL DEFAULT '',
    tags         TEXT[]      NOT NULL DEFAULT '{}',
    thumbnail    TEXT,                 -- which of the episode's pictures it gets: THUMBNAIL, THUMBNAIL_B or THUMBNAIL_C
    status       TEXT        NOT NULL CHECK (status IN ('QUEUED', 'UPLOADING', 'PROCESSING', 'DONE', 'FAILED', 'CANCELLED')),
    percent      INT,                  -- how much of the file is sent, by tens
    error        TEXT,                 -- why it failed
    note         TEXT,                 -- what didn't go with it, e.g. its thumbnail
    video_id     TEXT,                 -- YouTube's, once the file is there
    workflow_id  TEXT        NOT NULL, -- Temporal's, youtube-upload-<media ID>
    requester_id TEXT,                 -- Keycloak user ID (sub) of who asked for it
    requester    TEXT,                 -- their username at the time
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,          -- when it started being sent
    finished_at  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX youtube_uploads_episode_id_idx ON youtube_uploads (episode_id, created_at);
CREATE INDEX youtube_uploads_created_at_idx ON youtube_uploads (created_at);
-- A file is sent once at a time, as its workflow's ID says too
CREATE UNIQUE INDEX youtube_uploads_on_idx ON youtube_uploads (media_id)
    WHERE status IN ('QUEUED', 'UPLOADING', 'PROCESSING');

CREATE TRIGGER youtube_uploads_set_updated_at
    BEFORE UPDATE
    ON youtube_uploads
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Into audit_log, and so into the live updates; the histories leave them out, as the video says what was uploaded
CREATE TRIGGER youtube_uploads_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON youtube_uploads
    FOR EACH ROW
EXECUTE FUNCTION audit_row_change();
