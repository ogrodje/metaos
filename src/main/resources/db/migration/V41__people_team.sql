-- Who's on Ogrodje's team, as the team says (OG-83): its founder, listed first, those on it now, and those who were
-- and aren't at the moment; or not on it, whatever their roles. Without one, a person's roles tell (lists.ts's kindOf).
ALTER TABLE people
    ADD COLUMN team TEXT CHECK (team IN ('FOUNDER', 'ACTIVE', 'PAST', 'NONE'));

-- As OG-83 asked, by their names, as they are on the People page
UPDATE people SET team = 'FOUNDER' WHERE name = 'Oto Brglez' AND deleted_at IS NULL;
UPDATE people SET team = 'PAST'
WHERE name IN ('Tjaž Eržen', 'Lara Kocbek', 'Oliver Butina') AND deleted_at IS NULL;
UPDATE people SET team = 'NONE' WHERE name = 'Blendor Sefaj' AND deleted_at IS NULL;
