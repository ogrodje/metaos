-- FRI's events were there twice (OG-67): its organisation has FRI in two links, https://www.fri.uni-lj.si/ and
-- https://fri.uni-lj.si, and the FRI scraper wrote each event's URL as the link it read it from had it, and took its
-- ID from that URL. Now it reads FRI from https://fri.uni-lj.si alone and writes its events' URLs without www. and a
-- slash at their end, so each is found under the ID the one without www. has: those with www. that have a twin go,
-- giving it their hiding and pick where it has none.

CREATE TEMPORARY TABLE fri_twins AS
SELECT www.id AS www_id, bare.id AS bare_id, www.hidden_at, www.promoted_at
FROM events www
         JOIN events bare
              ON bare.organisation_id = www.organisation_id
                  AND bare.source = 'FRI'
                  AND bare.id <> www.id
                  AND rtrim(lower(bare.url), '/') = rtrim(regexp_replace(lower(www.url), '^(https?://)www\.', '\1'), '/')
WHERE www.source = 'FRI'
  AND www.url ~* '^https?://www\.';

UPDATE events e
SET hidden_at   = coalesce(e.hidden_at, t.hidden_at),
    promoted_at = coalesce(e.promoted_at, t.promoted_at)
FROM fri_twins t
WHERE e.id = t.bare_id
  AND ((e.hidden_at IS NULL AND t.hidden_at IS NOT NULL) OR (e.promoted_at IS NULL AND t.promoted_at IS NOT NULL));

DELETE
FROM events
WHERE id IN (SELECT www_id FROM fri_twins);

DROP TABLE fri_twins;

-- Those with www. left, without a twin: their URLs as the scraper writes them now
UPDATE events
SET url        = rtrim(regexp_replace(url, '^(https?://)www\.', '\1', 'i'), '/'),
    source_url = 'https://fri.uni-lj.si'
WHERE source = 'FRI'
  AND url ~* '^https?://www\.';

UPDATE events
SET source_url = 'https://fri.uni-lj.si'
WHERE source = 'FRI'
  AND source_url IS DISTINCT FROM 'https://fri.uni-lj.si';
