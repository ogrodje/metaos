-- Where an organisation's events usually are (Events.kt, Event.place): its venue, e.g. Kovačnica, and its address, which
-- an event that doesn't say where it is goes by. Public, unlike its registered address (`address`), which is finance's.
ALTER TABLE organisations
    ADD COLUMN venue_name    TEXT,
    ADD COLUMN venue_address TEXT;
