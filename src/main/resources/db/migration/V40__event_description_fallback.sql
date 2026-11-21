ALTER TABLE events ADD COLUMN description_is_fallback BOOLEAN NOT NULL DEFAULT false;

-- Before Indico enrichment, SLAIF generated its lecturer-only descriptions with this prefix.
-- This backfill records existing metadata without changing event history or modification times.
ALTER TABLE events DISABLE TRIGGER USER;
UPDATE events SET description_is_fallback = true
WHERE source = 'SLAIF' AND description LIKE 'Izvajalci: %' AND description NOT LIKE E'%\n%';
ALTER TABLE events ENABLE TRIGGER USER;
