-- A preview's data to begin with (scripts in this directory, CLAUDE.md's Previews): made up, never anyone's real data,
-- and just enough for every page to have something on it. Loaded once into a new preview's database, after MetaOS
-- has migrated it; agents add what their change needs on top, through the pages, GraphQL or MCP.
--
-- Fixed IDs, so links in a preview's notes keep working after `just preview up --fresh`. Dates are relative to today,
-- so what's upcoming stays upcoming.
BEGIN;

INSERT INTO shows (id, name, color, language) VALUES
  ('5e000000-0000-4000-8000-000000000001', 'Zagon', '#e4007c', 'sl-SI'),
  ('5e000000-0000-4000-8000-000000000002', 'Borderless', '#1f6feb', 'en-GB');

-- The first is you: the preview's dev user has this email, so whoami and "my episodes" find it
INSERT INTO people (id, name, email, bio, homepage_url, linkedin_url) VALUES
  ('9e000000-0000-4000-8000-000000000001', 'Dev User', 'dev@metaos.local', 'The preview''s own user (dev / dev).', NULL, NULL),
  ('9e000000-0000-4000-8000-000000000002', 'Maja Kovač', NULL, 'Host. Writes software and talks about it.', 'https://example.com/maja', NULL),
  ('9e000000-0000-4000-8000-000000000003', 'Luka Novak', NULL, 'Co-host and producer.', NULL, 'https://www.linkedin.com/in/example-luka'),
  ('9e000000-0000-4000-8000-000000000004', 'dr. Ana Zupan', NULL, 'Researcher in distributed systems.', 'https://example.com/ana', NULL),
  ('9e000000-0000-4000-8000-000000000005', 'Peter Horvat', NULL, 'Founder of a made-up startup.', NULL, NULL),
  ('9e000000-0000-4000-8000-000000000006', 'Eva Krajnc', NULL, 'Designer.', NULL, NULL),
  ('9e000000-0000-4000-8000-000000000007', 'Jan Oblak', NULL, NULL, NULL, NULL),
  ('9e000000-0000-4000-8000-000000000008', 'Sara Mlakar', NULL, 'Platform engineer.', NULL, NULL);

INSERT INTO sessions (id, date, name, location, address) VALUES
  ('5c000000-0000-4000-8000-000000000001', CURRENT_DATE - 40, 'Studio, autumn', 'Studio', 'Trubarjeva 1, Ljubljana'),
  ('5c000000-0000-4000-8000-000000000002', CURRENT_DATE + 9, 'Preview Conf 2026', 'Cankarjev dom', 'Prešernova 10, Ljubljana');

INSERT INTO episodes (id, code, title, title_b, status, show_id, session_id, recording_date, aired_date, summary,
                      tiny_summary, youtube_url, spotify_url, recommended_by, added_date) VALUES
  ('e0000000-0000-4000-8000-000000000001', 'S01E01', 'Kako začeti z zagonskim podjetjem', NULL, 'PUBLISHED',
   '5e000000-0000-4000-8000-000000000001', NULL, CURRENT_DATE - 200, CURRENT_DATE - 180,
   'Peter pove, kako je začel, kaj bi danes naredil drugače in zakaj je prvi produkt zavrgel.',
   'Prvi koraki zagonskega podjetja.', 'https://www.youtube.com/watch?v=example0001', NULL, NULL, CURRENT_DATE - 220),
  ('e0000000-0000-4000-8000-000000000002', 'S01E02', 'Porazdeljeni sistemi brez bolečin', 'Konsenz za vsakogar', 'PUBLISHED',
   '5e000000-0000-4000-8000-000000000001', NULL, CURRENT_DATE - 120, CURRENT_DATE - 100,
   'Ana razloži, kaj je konsenz, zakaj so ure lažnive in kdaj potrebujete Raft.',
   'Konsenz, ure in Raft.', 'https://www.youtube.com/watch?v=example0002', 'https://open.spotify.com/episode/example0002', NULL, CURRENT_DATE - 140),
  ('e0000000-0000-4000-8000-000000000003', 'S02E01', 'Platform teams that people like', NULL, 'PUBLISHED',
   '5e000000-0000-4000-8000-000000000002', NULL, CURRENT_DATE - 70, CURRENT_DATE - 50,
   'Sara on building an internal platform that teams choose rather than put up with.',
   'Internal platforms done well.', 'https://www.youtube.com/watch?v=example0003', NULL, NULL, CURRENT_DATE - 90),
  ('e0000000-0000-4000-8000-000000000004', 'S01E03', 'Oblikovanje za razvijalce', NULL, 'EARLY_ACCESS',
   '5e000000-0000-4000-8000-000000000001', '5c000000-0000-4000-8000-000000000001', CURRENT_DATE - 40, NULL,
   'Eva o tem, kaj razvijalci lahko naredijo za boljši vmesnik, preden pride oblikovalec.', NULL, NULL, NULL, NULL, CURRENT_DATE - 60),
  ('e0000000-0000-4000-8000-000000000005', 'S01E04', 'Testi, ki ne lažejo', NULL, 'IN_PRODUCTION',
   '5e000000-0000-4000-8000-000000000001', '5c000000-0000-4000-8000-000000000001', CURRENT_DATE - 40, NULL,
   NULL, NULL, NULL, NULL, NULL, CURRENT_DATE - 55),
  ('e0000000-0000-4000-8000-000000000006', 'S01E05', 'Podatkovne baze v letu 2026', NULL, 'RECORDED',
   '5e000000-0000-4000-8000-000000000001', '5c000000-0000-4000-8000-000000000001', CURRENT_DATE - 40, NULL,
   NULL, NULL, NULL, NULL, NULL, CURRENT_DATE - 50),
  ('e0000000-0000-4000-8000-000000000007', 'S01E06', 'V živo s konference', NULL, 'SCHEDULED',
   '5e000000-0000-4000-8000-000000000001', '5c000000-0000-4000-8000-000000000002', CURRENT_DATE + 9, NULL,
   NULL, NULL, NULL, NULL, NULL, CURRENT_DATE - 20),
  ('e0000000-0000-4000-8000-000000000008', NULL, 'Odprta koda kot posel', NULL, 'IDEA',
   '5e000000-0000-4000-8000-000000000001', NULL, NULL, NULL,
   NULL, 'Kako se z odprto kodo preživiš.', NULL, NULL, '9e000000-0000-4000-8000-000000000003', CURRENT_DATE - 10),
  ('e0000000-0000-4000-8000-000000000009', NULL, 'Remote teams across borders', NULL, 'IDEA',
   '5e000000-0000-4000-8000-000000000002', NULL, NULL, NULL,
   NULL, NULL, NULL, NULL, '9e000000-0000-4000-8000-000000000001', CURRENT_DATE - 3);

INSERT INTO episode_people (id, episode_id, person_id, role) VALUES
  ('ee000000-0000-4000-8000-000000000001', 'e0000000-0000-4000-8000-000000000001', '9e000000-0000-4000-8000-000000000002', 'HOST'),
  ('ee000000-0000-4000-8000-000000000002', 'e0000000-0000-4000-8000-000000000001', '9e000000-0000-4000-8000-000000000003', 'CO_HOST'),
  ('ee000000-0000-4000-8000-000000000003', 'e0000000-0000-4000-8000-000000000001', '9e000000-0000-4000-8000-000000000005', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000002', '9e000000-0000-4000-8000-000000000002', 'HOST'),
  ('ee000000-0000-4000-8000-000000000005', 'e0000000-0000-4000-8000-000000000002', '9e000000-0000-4000-8000-000000000004', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000006', 'e0000000-0000-4000-8000-000000000002', '9e000000-0000-4000-8000-000000000003', 'PRODUCER'),
  ('ee000000-0000-4000-8000-000000000007', 'e0000000-0000-4000-8000-000000000003', '9e000000-0000-4000-8000-000000000003', 'HOST'),
  ('ee000000-0000-4000-8000-000000000008', 'e0000000-0000-4000-8000-000000000003', '9e000000-0000-4000-8000-000000000008', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000009', 'e0000000-0000-4000-8000-000000000004', '9e000000-0000-4000-8000-000000000002', 'HOST'),
  ('ee000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000004', '9e000000-0000-4000-8000-000000000006', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000004', '9e000000-0000-4000-8000-000000000006', 'DESIGNER'),
  ('ee000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000005', '9e000000-0000-4000-8000-000000000002', 'HOST'),
  ('ee000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000005', '9e000000-0000-4000-8000-000000000007', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000005', '9e000000-0000-4000-8000-000000000001', 'PRODUCER'),
  ('ee000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000006', '9e000000-0000-4000-8000-000000000003', 'HOST'),
  ('ee000000-0000-4000-8000-000000000016', 'e0000000-0000-4000-8000-000000000006', '9e000000-0000-4000-8000-000000000004', 'GUEST'),
  ('ee000000-0000-4000-8000-000000000017', 'e0000000-0000-4000-8000-000000000007', '9e000000-0000-4000-8000-000000000002', 'HOST'),
  ('ee000000-0000-4000-8000-000000000018', 'e0000000-0000-4000-8000-000000000007', '9e000000-0000-4000-8000-000000000005', 'CANDIDATE'),
  ('ee000000-0000-4000-8000-000000000019', 'e0000000-0000-4000-8000-000000000008', '9e000000-0000-4000-8000-000000000007', 'CANDIDATE'),
  ('ee000000-0000-4000-8000-000000000020', 'e0000000-0000-4000-8000-000000000003', '9e000000-0000-4000-8000-000000000001', 'CONSULTANT');

-- Made-up meetups whose events are on the Events page (V36), and their events, from now on so they stay upcoming;
-- with how their links were last read, as the scrapers would have, as previews have no Temporal to read them
INSERT INTO organisations (id, name, tier, website_url, meetup_url, ical_url, main_color, background_color, description,
                           collects_events, archived_at) VALUES
  ('0a000000-0000-4000-8000-000000000001', 'Kotlin Ljubljana (preview)', 'NONE', 'https://example.com/kotlin',
   'https://www.meetup.com/kotlin-ljubljana-preview/', NULL, '#ffffff', '#7f52ff', 'A made-up meetup', TRUE, NULL),
  ('0a000000-0000-4000-8000-000000000002', 'Hekerji Maribor (preview)', 'NONE', 'https://example.com/hekerji',
   NULL, 'https://example.com/hekerji/cal.ics', NULL, NULL, 'A made-up hacker space', TRUE, NULL),
  ('0a000000-0000-4000-8000-000000000003', 'Old Meetup (preview)', 'NONE', 'https://example.com/old',
   'https://www.meetup.com/old-preview/', NULL, NULL, NULL, 'Archived: its events are no longer gathered', FALSE, now());

-- Where Kotlin Ljubljana's events usually are (V37), which its event that doesn't say where it is goes by
UPDATE organisations SET venue_name = 'Kovačnica', venue_address = 'Tržaška cesta 2, Ljubljana'
WHERE id = '0a000000-0000-4000-8000-000000000001';

INSERT INTO events (id, organisation_id, source, external_id, source_url, title, description, url, starts_at, ends_at,
                    has_start_time, has_end_time, location_name, location_address, hidden_at, promoted_at) VALUES
  ('0e000000-0000-4000-8000-000000000001', '0a000000-0000-4000-8000-000000000001', 'MEETUP', 'meetup-preview-1',
   'https://www.meetup.com/kotlin-ljubljana-preview/events', 'Coroutines in practice', 'Talks and pizza',
   'https://www.meetup.com/kotlin-ljubljana-preview/events/1', date_trunc('day', now()) + interval '2 days 17 hours',
   date_trunc('day', now()) + interval '2 days 20 hours', TRUE, TRUE, 'Kovačnica', 'Tržaška cesta 2, Ljubljana', NULL, now()),
  ('0e000000-0000-4000-8000-000000000002', '0a000000-0000-4000-8000-000000000001', 'MEETUP', 'meetup-preview-2',
   'https://www.meetup.com/kotlin-ljubljana-preview/events', 'Kotlin Multiplatform night', NULL,
   'https://www.meetup.com/kotlin-ljubljana-preview/events/2', date_trunc('day', now()) + interval '9 days 17 hours',
   NULL, TRUE, FALSE, NULL, NULL, NULL, NULL),
  ('0e000000-0000-4000-8000-000000000003', '0a000000-0000-4000-8000-000000000002', 'ICAL', 'ical-preview-1',
   'https://example.com/hekerji/cal.ics', 'Hackathon weekend', 'Two days of hacking', 'https://example.com/hekerji/hackathon',
   date_trunc('day', now()) + interval '12 days', date_trunc('day', now()) + interval '13 days', FALSE, FALSE,
   'Hekerji', 'Glavni trg 1, Maribor', NULL, NULL),
  ('0e000000-0000-4000-8000-000000000004', '0a000000-0000-4000-8000-000000000002', 'ICAL', 'ical-preview-2',
   'https://example.com/hekerji/cal.ics', 'Spam event, hidden by the team', NULL, NULL,
   date_trunc('day', now()) + interval '5 days 16 hours', NULL, TRUE, FALSE, NULL, NULL, now(), NULL),
  ('0e000000-0000-4000-8000-000000000005', '0a000000-0000-4000-8000-000000000002', 'MANUAL', NULL,
   NULL, 'Ogrodje meetup (added by hand)', NULL, 'https://ogrodje.si',
   date_trunc('day', now()) + interval '6 days 17 hours', date_trunc('day', now()) + interval '6 days 19 hours',
   TRUE, TRUE, NULL, NULL, NULL, NULL),
  ('0e000000-0000-4000-8000-000000000006', '0a000000-0000-4000-8000-000000000002', 'ICAL', 'ical-preview-3',
   'https://example.com/hekerji/cal.ics', 'Capture the flag, online', NULL, 'https://example.com/hekerji/ctf',
   date_trunc('day', now()) + interval '4 days 18 hours', date_trunc('day', now()) + interval '4 days 21 hours',
   TRUE, TRUE, 'Online', NULL, NULL, NULL),
  ('0e000000-0000-4000-8000-000000000007', '0a000000-0000-4000-8000-000000000001', 'MEETUP', 'meetup-preview-3',
   'https://www.meetup.com/kotlin-ljubljana-preview/events', 'Kotlin on tour: Kranj', NULL,
   'https://www.meetup.com/kotlin-ljubljana-preview/events/3', date_trunc('day', now()) + interval '16 days 17 hours',
   NULL, TRUE, FALSE, 'Kovačnica Kranj', 'Poštna ulica 4, 4000 Kranj', NULL, NULL);

-- Their regions (V39), for the Events page's filter, as MetaOS tells them when it keeps them: the organisations' from
-- their venues, the events' from where they say they are; the one added by hand, which doesn't, has none
UPDATE organisations SET region = region_of(venue_name, venue_address) WHERE venue_name IS NOT NULL OR venue_address IS NOT NULL;
UPDATE events SET region = region_of(location_name, location_address) WHERE location_name IS NOT NULL OR location_address IS NOT NULL;

INSERT INTO event_scrapes (organisation_id, field, url, source, scraped_at, events, error) VALUES
  ('0a000000-0000-4000-8000-000000000001', 'WEBSITE', 'https://example.com/kotlin', NULL, now() - interval '20 minutes', 0, NULL),
  ('0a000000-0000-4000-8000-000000000001', 'MEETUP', 'https://www.meetup.com/kotlin-ljubljana-preview/', 'MEETUP',
   now() - interval '20 minutes', 2, NULL),
  ('0a000000-0000-4000-8000-000000000002', 'WEBSITE', 'https://example.com/hekerji', NULL, now() - interval '20 minutes', 0, NULL),
  ('0a000000-0000-4000-8000-000000000002', 'ICAL', 'https://example.com/hekerji/cal.ics', NULL, now() - interval '20 minutes', 0,
   'https://example.com/hekerji/cal.ics answered 503');

COMMIT;
