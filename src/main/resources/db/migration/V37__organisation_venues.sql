-- Where an organisation's events usually are (Events.kt, Event.place): its venue, e.g. Kovačnica, and its address, which
-- an event that doesn't say where it is goes by. Public, unlike its registered address (`address`), which is finance's.
ALTER TABLE organisations
    ADD COLUMN venue_name    TEXT,
    ADD COLUMN venue_address TEXT;

-- An organisation gathers events once it has somewhere to read them, not by default: V35's supporters and partners,
-- and the companies people work at, have only their websites, where they don't announce events. Goo's meetups gather
-- theirs (GooImport.kt), as does one given an event link (Organisations.create).
ALTER TABLE organisations ALTER COLUMN collects_events SET DEFAULT false;
UPDATE organisations SET collects_events = false
WHERE goo_id IS NULL AND meetup_url IS NULL AND eventbrite_url IS NULL AND ical_url IS NULL AND luma_url IS NULL
  AND kompot_url IS NULL;
