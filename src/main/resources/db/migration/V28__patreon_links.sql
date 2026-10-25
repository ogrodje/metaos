-- Patreon has four of each episode: the full episode and its bonus, each as video and audio only. The link there was,
-- patreon_url, stays the full episode as video.
ALTER TABLE episodes
    ADD COLUMN patreon_audio_url       TEXT,
    ADD COLUMN patreon_bonus_url       TEXT,
    ADD COLUMN patreon_bonus_audio_url TEXT;
