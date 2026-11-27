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

-- The team, as it says (V41): Maja founded it, Eva isn't on it at the moment; the rest go by their roles
UPDATE people SET team = 'FOUNDER' WHERE id = '9e000000-0000-4000-8000-000000000002';
UPDATE people SET team = 'PAST' WHERE id = '9e000000-0000-4000-8000-000000000006';

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

-- A copy of some of production's organisations and upcoming events, from MetaOS' MCP (list_organisations, list_events) on
-- 2026-11-27: public data, as the Events page shows it, with long descriptions cut short, and the dates moved so they're
-- as far from the day it's loaded as they were from that day. IDs are production's.
INSERT INTO organisations (id, name, tier, website_url, meetup_url, ical_url, luma_url, eventbrite_url, main_color,
                           background_color, venue_name, venue_address, collects_events) VALUES
  ('979d9215-4efd-469c-a268-ee8d377c515e', $q$Katapult$q$, 'NONE', $q$https://katapult.si$q$, NULL, NULL, NULL, NULL, NULL, NULL, $q$Katapult, Gabrsko 12, Trbovlje$q$, NULL, TRUE),
  ('44a88f6b-720c-4acc-bd36-df6e5de09f34', $q$SAŠA inkubator$q$, 'NONE', $q$https://sasainkubator.si$q$, NULL, $q$https://sasainkubator.si/dogodki/seznam/?ical=1$q$, NULL, NULL, $q$#ffffff$q$, $q$#000000$q$, $q$Saša Inkubator$q$, $q$Šaleška cesta 2A, 3320 Velenje$q$, TRUE),
  ('1c94206c-d7b3-452c-860f-6ff8e76ef5a0', $q$Slovenia Games Meetup$q$, 'NONE', $q$https://sloveniagames.com/$q$, $q$https://www.meetup.com/sloveniagames/$q$, NULL, NULL, NULL, $q$#298e55$q$, $q$#ffffff$q$, NULL, NULL, TRUE),
  ('4dc05734-5010-4782-9b3a-18c62ba7265c', $q$Primorski tehnološki park$q$, 'NONE', $q$https://www.primorski-tp.si/$q$, NULL, NULL, NULL, NULL, $q$#ffffff$q$, $q$#0130c6$q$, NULL, NULL, TRUE),
  ('a148acaf-b0b1-4892-96d8-155a4f4ff1d9', $q$Finance / IKTinformator$q$, 'NONE', $q$https://www.finance.si/ikt$q$, NULL, $q$https://calendar.google.com/calendar/ical/2h6f6avuude6mgk6ct9b4h5a6g%40group.calendar.google.com/public/basic.ics$q$, NULL, NULL, $q$#ffffff$q$, $q$#c00000$q$, NULL, NULL, TRUE),
  ('10e4a705-487f-47a3-b57e-3ade0d6fcc1a', $q$KCUI$q$, 'NONE', $q$https://kcui.si/$q$, NULL, NULL, NULL, NULL, $q$#ffffff$q$, $q$#e63920$q$, NULL, NULL, TRUE),
  ('5f033b74-d300-44e4-bd3e-3d2bef5d23c8', $q$GZS ZIT$q$, 'NONE', $q$https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/$q$, NULL, NULL, NULL, NULL, $q$#ffffff$q$, $q$#a6ce39$q$, NULL, NULL, TRUE),
  ('c686f33d-2158-4291-a8e6-7c8662be73c5', $q$UL FRI$q$, 'NONE', $q$https://www.fri.uni-lj.si/$q$, NULL, NULL, NULL, NULL, $q$#000000$q$, $q$#ffffff$q$, $q$Fakulteta za računalništvo in informatiko (UL)$q$, $q$Večna pot 113, Ljubljana$q$, TRUE),
  ('1ef92e60-b197-410e-97d9-da8e77826225', $q$Start:up Slovenija$q$, 'NONE', $q$https://www.startup.si$q$, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, TRUE),
  ('2d34c958-80aa-47ad-9ffa-cf6d9383963a', $q$Podjetniški inkubator Podbreznik (RC Novo mesto)$q$, 'NONE', $q$https://www.rc-nm.si$q$, NULL, NULL, NULL, NULL, $q$#ffffff$q$, $q$#6f2c8e$q$, $q$Podjetniški inkubator Podbreznik$q$, $q$Podbreznik 15, 8000 Novo mesto$q$, TRUE),
  ('b5cfb2db-7edf-49a8-b1ea-b004b9cbe888', $q$Tehnološki park Ljubljana$q$, 'NONE', $q$https://www.tp-lj.si$q$, NULL, NULL, NULL, NULL, $q$#82bc00$q$, $q$#ffffff$q$, NULL, NULL, TRUE),
  ('cf8504aa-40f1-4019-b314-096e19b93a9f', $q$Slovenska tovarna UI (SLAIF)$q$, 'NONE', $q$https://www.slaif.si$q$, NULL, NULL, NULL, NULL, $q$#abc637$q$, $q$#ffffff$q$, NULL, NULL, TRUE);

-- Three of them are the partners V35 made
UPDATE organisations SET luma_url = NULL, eventbrite_url = NULL, main_color = $q$#ffffff$q$, background_color = $q$#e41061$q$, collects_events = TRUE WHERE id = '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0007';
UPDATE organisations SET luma_url = $q$https://luma.com/sloai$q$, eventbrite_url = $q$https://www.eventbrite.com/o/slovenia-ai-meetup-104538676951$q$, main_color = $q$#ffffff$q$, background_color = $q$#111827$q$, collects_events = TRUE WHERE id = '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0008';
UPDATE organisations SET luma_url = NULL, eventbrite_url = NULL, main_color = $q$#1e2450$q$, background_color = $q$#ffffff$q$, collects_events = TRUE WHERE id = '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0006';

INSERT INTO events (id, organisation_id, source, external_id, source_url, title, description, url, starts_at, ends_at,
                    has_start_time, has_end_time, location_name, location_address, promoted_at) VALUES
  ('aaba1dd1-ca2d-48c0-97a7-8c7633d4d5a7', '44a88f6b-720c-4acc-bd36-df6e5de09f34', 'ICAL', 'production-aaba1dd1', $q$https://sasainkubator.si/dogodki/seznam/?ical=1$q$, $q$Marketing brez kričanja$q$, $q$MAJA ŠVENER - STARTUP LIGHTUP - SAŠA INKUBATOR

Eden naših startupov ima problem z našo gostjo, predavateljico, podjetnico Majo Švener! A si jo torej 2.12.2026 ob 17:00 upamo povabit na STARTUP LIGHTUP pogovor k nam v SAŠA inkubator?

Majo smo pri delu z našimi inovativnimi startupi videli že večkrat in če kaj, nam je jasno to, da pri njej ne boste dobili dolgih predavanj o tem, kaj bi morda lahko delovalo. Ker ona pove naravnost, tudi takrat, ko odgovor ni najbolj prijeten.$q$, $q$https://sasainkubator.si/dogodki/marketing-brez-kricanja/$q$, date_trunc('day', now()) + interval '-1 days 16 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '12 days 19 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Saša Inkubator$q$, $q$Šaleška cesta 2A, 3320 Velenje$q$, NULL),
  ('b327965e-8b2d-4129-ba15-79388cb675fd', '1c94206c-d7b3-452c-860f-6ff8e76ef5a0', 'MEETUP', 'production-b327965e', $q$https://www.meetup.com/sloveniagames/events$q$, $q$Slovenia Game Dev Meetup - November 2026$q$, $q$🎲 Kako nastane odlična igra? Pridruži se nam na novemberskem Slovenia Game Dev Meetupu! 🎮

Tokrat bo z nami Primož Černelč, priznani avtor številnih namiznih iger. Primož nas bo popeljal v zakulisje svojega ustvarjalnega procesa – razkril bo, kje črpa navdih, kako v praksi testira svoje ideje in kako jih uspešno vpelje v končne projekte.

📅 Kdaj: Petek, 27. november, ob 19.00
📍 Kje: Studio Dansa, Vilharjeva 3, Ljubljana
🎟 Vstop: Prost

ENG:
🎲 How is a great game made? Join us at the November's Slovenia Game Dev Meetup! 🎮

This time, we're hosting Primož Černelč, an acclaimed board game designer.$q$, $q$https://www.meetup.com/sloveniagames/events/317437847/$q$, date_trunc('day', now()) + interval '0 days 18 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '0 days 21 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Studio Dansa$q$, $q$Vilharjeva cesta 3, 1000 Ljubljana$q$, NULL),
  ('8a2598ee-b741-487c-80ef-ab06399a6e7c', '4dc05734-5010-4782-9b3a-18c62ba7265c', 'PRIMORSKI_TEHNOLOSKI_PARK', 'production-8a2598ee', $q$https://www.primorski-tp.si/dogodek/hackatontvoje-znanje-realen-izziv-nove-priloznosti/$q$, $q$TECH HACKATON 2026$q$, $q$&lt;p&gt;Imaš ZNANJE. Pridruži se IZZIVu. Odpri si vrata do NOVIH PRILOŽNOSTI. Želiš svoje tehnično znanje spremeniti v delujoč prototip? Preizkusi se na enodnevnem tech challenge hackathonu, kjer boš reševal/-a resničen [&hellip;]&lt;/p&gt;\n$q$, $q$https://www.primorski-tp.si/dogodek/hackatontvoje-znanje-realen-izziv-nove-priloznosti/$q$, date_trunc('day', now()) + interval '0 days 23 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '1 days 22 hours 59 minutes 59 seconds', TRUE, TRUE, NULL, NULL, now()),
  ('d3870a8b-7ad6-4539-86ab-c0fc6b5cb15b', '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0007', 'RACUNALNISKI_MUZEJ', 'production-d3870a8b', $q$https://www.racunalniski-muzej.si/event/$q$, $q$Medgeneracijska delavnica za skupno pot po digitalni mladosti$q$, $q$Na prvih dveh uspešnih Terenskih vodnikih po digitalni mladosti: "Kako postaviti meje?" in "Kaj nas čaka jutri?", smo se zbrali odrasli vzorniki otrok in mladih. Tokrat pa se v sodelovanju s Četrtno skupnostjo Šiška na dogodku dobimo vsi skupaj. Na predavanje in delavnico so vabljeni OTROCI IN MLADI s svojimi starši, starimi starši, tetami in$q$, $q$https://www.racunalniski-muzej.si/event/medgeneracijska-delavnica-za-skupno-pot-po-digitalni-mladosti/$q$, date_trunc('day', now()) + interval '1 days 10 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '1 days 11 hours 30 minutes 0 seconds', TRUE, TRUE, $q$1. nadstropje$q$, NULL, NULL),
  ('15377e12-84c7-4f7f-87a6-59819d7b0245', '979d9215-4efd-469c-a268-ee8d377c515e', 'KATAPULT', 'production-15377e12', $q$https://katapult.si/dogodki$q$, $q$Napredna uporaba Clauda za optimizacijo pogostih nalog pri poslovanju$q$, $q$Umetno inteligenco danes uporablja skoraj vsak, a večina ostane pri občasnem klepetu, s čimer je težko zares optimizirati delovne procese. Na tej delavnici gremo korak dlje: pogledali si bomo, kako Claude uporabiti kot orodje za ponovljive delovne procese in ga povezati z orodji, ki jih že uporabljate.$q$, $q$https://katapult.si/novice/napredna-uporaba-clauda-30-11-2026$q$, date_trunc('day', now()) + interval '3 days 08 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '3 days 11 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Katapult$q$, $q$Gabrsko 12, Trbovlje$q$, NULL),
  ('0f6ace55-0589-4268-8639-bb311c24ff40', 'a148acaf-b0b1-4892-96d8-155a4f4ff1d9', 'ICAL', 'production-0f6ace55', $q$https://calendar.google.com/calendar/ical/2h6f6avuude6mgk6ct9b4h5a6g%40group.calendar.google.com/public/basic.ics$q$, $q$Podatkovno podprto odločanje z BI in AI orodji$q$, $q$Brezplačni seminar podjetja B2 BI, namenjen vodstvenim kadrom. Rdeča nit seminarja bo, kako vzpostaviti poslovanje, kjer podatki dejansko pomagajo vodstvu pri odločanju, <a href="https://go.b2-bi.com/sl/data-driven-z-bi-in-ai-dec-26">go.b2-bi.com</a>$q$, $q$https://go.b2-bi.com/sl/data-driven-z-bi-in-ai-dec-26$q$, date_trunc('day', now()) + interval '3 days 23 hours 01 minutes 0 seconds', date_trunc('day', now()) + interval '4 days 22 hours 59 minutes 0 seconds', FALSE, FALSE, $q$Hotel City Maribor$q$, $q$Ulica kneza Koclja 22, 2000 Maribor$q$, NULL),
  ('92cb9d6c-ad8a-4667-a524-4bd5f1ff51de', '10e4a705-487f-47a3-b57e-3ade0d6fcc1a', 'KCUI', 'production-92cb9d6c', $q$https://kcui.si/izobrazevanja$q$, $q$Uvod v model ocene zrelosti organizacije na področju umetne inteligence$q$, $q$Uvodna delavnica in webinar za oceno pripravljenosti in zrelosti organizacije na področju UI. Spoznajte strukturiran postopek v petih korakih: uvod v model, gradiva in smernice, samoocena, analiza rezultatov ter interpretacija z nadaljnjimi ukrepi.$q$, $q$https://kcui.si/izobrazevanja/uvod-v-model-ocene-zrelosti-organizacije-na-podrocju-umetne-inteligence$q$, date_trunc('day', now()) + interval '4 days 12 hours 00 minutes 0 seconds', NULL, TRUE, FALSE, $q$Online$q$, NULL, NULL),
  ('c6fdf396-9486-48d5-9b0a-63e59d60bd9f', '5f033b74-d300-44e4-bd3e-3d2bef5d23c8', 'GZS', 'production-c6fdf396', $q$https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki$q$, $q$KCUI Spletni seminar: Uvod v model ocene zrelosti organizacije na področju umetne inteligence$q$, $q$Uvodna delavnica in webinar za oceno pripravljenosti in zrelosti organizacije na področju UI. Spoznajte strukturiran postopek v petih korakih: uvod v model, gradiva in smernice, samoocena, analiza rezultatov ter interpretacija z nadaljnjimi ukrepi.$q$, $q$https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki?ID=7363$q$, date_trunc('day', now()) + interval '4 days 12 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '4 days 13 hours 30 minutes 0 seconds', TRUE, TRUE, $q$Online$q$, NULL, NULL),
  ('bab08db6-4ab3-4cdd-a4e2-d3d24f5fa782', 'c686f33d-2158-4291-a8e6-7c8662be73c5', 'FRI', 'production-bab08db6', $q$https://fri.uni-lj.si$q$, $q$Dan FRI 2026$q$, $q$Vljudno vabljeni na slavnostni dogodek in druženje ob Dnevu Fakultete za računalništvo in informatiko Univerze v Ljubljani, da v sproščenem vzdušju skupaj proslavimo pretekle dosežke fakultete, njenih zaposlenih in študentov.

Dogodek bo v torek, 1. decembra 2026, ob 14. uri v predavalnici 1 na UL FRI, Večna pot 113, v Ljubljani.

Skupaj se bomo spomnili vrhuncev preteklega leta in razvoja na ključnih področjih, nato pa bomo podelili:

Pohvale študentom za zgledno opravljene študijske obveznosti

Fakultetne Prešernove nagrade študentom

Po podelitvi sledi druženje ob pogostivi v avli FRI.$q$, $q$https://fri.uni-lj.si/sl/dogodek/dan-fri-2026$q$, date_trunc('day', now()) + interval '4 days 13 hours 00 minutes 0 seconds', NULL, TRUE, FALSE, $q$Fakulteta za računalništvo in informatiko (UL)$q$, $q$Večna pot 113, 1000 Ljubljana$q$, NULL),
  ('dc843aeb-990b-49f9-9c82-a62a4213c505', '1ef92e60-b197-410e-97d9-da8e77826225', 'STARTUP_SI', 'production-dc843aeb', $q$https://www.startup.si/sl-si/dogodki$q$, $q$POZABI NA STARTUP PRAVILA$q$, $q$Od nič do 50+ zaposlenih. Brez klasičnega startup recepta.

Kako iz Slovenije zgraditi globalni biznis, ki raste po svojih pravilih?

Najprej MVP. Potem pitch deck. Investitorji. Funding round. Skaliranje.

Tako naj bi se gradil uspešen startup. Toda kaj, če namesto investitorjev najprej poiščeš kupce in z njihovim denarjem financiraš svoje naslednje velike ideje?

Žiga Fajfar in Sergej Gorišek sta s soustanoviteljem Luko Mlakarjem zgradila Flowout, mednarodno Webflow agencijo z več kot 50 zaposlenimi, ki je sodelovala s stotinami podjetij po svetu. S prihodki agencije so financirali tudi razvoj lastnih SaaS produktov, med drugim Vidzflow in Optibase.

A rast je prinesla tudi napačne odločitve pri zaposlovanju, neuspele ideje, težke odločitve in konkurenco velikih tehnoloških igralcev.

Na naslednjem Startup:up Talku by Start:up Slovenia bomo z Žigo in Sergejem brez olepševanja govorili o gradnji globalnega podjetja, izzivih hitre rasti, vplivu AI-ja na razvoj ekip in podjetniških lekcijah, ki jih ne najdeš v priročnikih.

📅 1. december 2026 ob 18.00
📍 Sodni stolp, Maribor

Dogodek je brezplačen, prijava obvezna.

VSTOPNICA

Pet vprašanj. Pet razlogov, da prideš.

01 Zakaj iskati investitorja, če lahko svoj startup financiraš s kupci?

Flowout ni nastal samo zato, da bi gradili spletne strani za naročnike. Že od začetka je bila ideja, da prihodki agencije financirajo razvoj lastnih produktov. Kako zgraditi dobičkonosen posel in hkrati vlagati v nove ideje? Kdaj je bootstrapping konkurenčna prednost in kdaj bi lahko investitor pospešil rast?

02 Od nič do 50+ zaposlenih. Kaj bi danes naredila drugače?

Na začetku vsi delajo vse. Potem pridejo novi zaposleni, vodje, procesi in vedno večja odgovornost.$q$, $q$https://www.startup.si/sl-si/dogodek/pozabi-na-startup-pravila$q$, date_trunc('day', now()) + interval '4 days 17 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '4 days 20 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Sodni stolp$q$, $q$Pristan 8, Maribor$q$, NULL),
  ('9491b8ee-ca3d-4119-bf97-184d24790205', '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0008', 'LUMA', 'production-9491b8ee', $q$https://luma.com/sloai$q$, $q$SLOAI x sference: What a Trillion Tokens Taught Us About Inference$q$, NULL, $q$https://luma.com/5j90j5kn$q$, date_trunc('day', now()) + interval '4 days 17 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '4 days 19 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Ljubljana.tech$q$, $q$Trg mladih 9, 1000 Ljubljana$q$, now()),
  ('84ceb2e7-943d-4506-a424-b05cfaafd155', '6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0006', 'SPLETNE_URICE', 'production-84ceb2e7', $q$https://wwwhmb.si/predavanja/$q$, $q$#176: Firma na avtopilotu?$q$, $q$Programirati z AI zna že skoraj vsak. A kaj, ko je treba z njim voditi še podjetje?$q$, $q$https://wwwhmb.si/176-firma-na-avtopilotu/$q$, date_trunc('day', now()) + interval '4 days 18 hours 05 minutes 0 seconds', NULL, TRUE, FALSE, $q$BarCoda, Vetrinjc$q$, NULL, now()),
  ('627444b4-cb09-4815-88d7-f5c6031331c3', '2d34c958-80aa-47ad-9ffa-cf6d9383963a', 'PODJETNISKI_INKUBATOR_PODBREZNIK', 'production-627444b4', $q$https://www.rc-nm.si/dogodki/$q$, $q$Od dobrih praks do novih povezovanj$q$, $q$Vabimo na obisk dobrih praks socialnega podjetništva, kjer bomo spoznali zanimive podjetniške zgodbe in se povezali z ljudmi, ki soustvarjajo družbeno odgovorno podjetniško okolje.$q$, $q$https://www.rc-nm.si/dogodki/od-dobrih-praks-do-novih-povezovanj/$q$, date_trunc('day', now()) + interval '5 days 06 hours 30 minutes 0 seconds', date_trunc('day', now()) + interval '10 days 13 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Online$q$, NULL, NULL),
  ('12018777-03a9-46af-9ec5-c6281705cf0e', 'b5cfb2db-7edf-49a8-b1ea-b004b9cbe888', 'TEHNOLOSKI_PARK_LJUBLJANA', 'production-12018777', $q$https://www.tp-lj.si/sl/koledar-dogodkov$q$, $q$Marketing 2027: Kako graditi splet za ljudi, Google in AI$q$, $q$Ali bodo spletne strani v dobi umetne inteligence sploh še pomembne?
Način, kako ljudje iščejo informacije, odkrivajo podjetja in sprejemajo odločitve, se hitro spreminja. Z njim pa tudi pravila digitalnega marketinga.$q$, $q$https://www.tp-lj.si/sl/dogodki/marketing-2027$q$, date_trunc('day', now()) + interval '5 days 15 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '5 days 17 hours 00 minutes 0 seconds', TRUE, FALSE, NULL, NULL, NULL),
  ('a6daa165-975d-4704-92e3-164bb5001a55', '10e4a705-487f-47a3-b57e-3ade0d6fcc1a', 'KCUI', 'production-a6daa165', $q$https://kcui.si/izobrazevanja$q$, $q$Evolucija.AI: Povezane$q$, $q$Ženske, ki oblikujejo prihodnost umetne inteligence. Srečanje KCUI, ki bo 10. decembra 2026 povezalo ženske iz gospodarstva, raziskovalnega okolja, prava in družboslovja.$q$, $q$https://kcui.si/izobrazevanja/evolucija-ai-povezane$q$, date_trunc('day', now()) + interval '13 days 12 hours 30 minutes 0 seconds', NULL, TRUE, FALSE, $q$Gospodarska zbornica Slovenije, dvorana C$q$, NULL, NULL),
  ('29e39aa1-bc99-4f6d-89f4-2fe0cc305f43', '1ef92e60-b197-410e-97d9-da8e77826225', 'STARTUP_SI', 'production-29e39aa1', $q$https://www.startup.si/sl-si/dogodki$q$, $q$Kako z umetno inteligenco razvijati ideje in reševati probleme?$q$, $q$Kaj če bi imel pri razvoju nove ideje vedno na voljo sogovornika, ki bi izpodbijal tvoje predpostavke, predlagal drugačne rešitve in ti pomagal hitreje preveriti zamisli? Umetna inteligenca lahko prevzame prav to vlogo. Na brezplačnem spletnem dogodku “Develop Your Ideas with AI - Playbook Launch” bo 11. decembra 2026 predstavljen nov praktični priročnik (playbook).$q$, $q$https://www.startup.si/sl-si/dogodek/kako-z-umetno-inteligenco-razvijati-ideje-in-resevati-probleme-$q$, date_trunc('day', now()) + interval '14 days 07 hours 15 minutes 0 seconds', date_trunc('day', now()) + interval '14 days 08 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Online$q$, NULL, NULL),
  ('9fce06c3-9d8a-4dc6-9632-47ff5235faa3', 'b5cfb2db-7edf-49a8-b1ea-b004b9cbe888', 'TEHNOLOSKI_PARK_LJUBLJANA', 'production-9fce06c3', $q$https://www.tp-lj.si/sl/koledar-dogodkov$q$, $q$Tehnološki Dedek Mraz in čarovnija znanosti$q$, $q$Signal Dedka Mraza je ujet, a kje v Tehnoparku Celje se skriva? Škratinja Iskra, robotka Barbara in kuža Kodi potrebujejo pomoč radovednih očes. Pridružite se nam v sredo, 16. decembra 2026, ob 19.30 na brezplačni spletni predstavi za otroke med 5. in 10. letom.$q$, $q$https://www.tp-lj.si/sl/dogodki/tehnoloski-dedek-mraz-in-carovnija-znanosti-2026-11-27$q$, date_trunc('day', now()) + interval '19 days 18 hours 30 minutes 0 seconds', date_trunc('day', now()) + interval '19 days 20 hours 30 minutes 0 seconds', TRUE, FALSE, NULL, NULL, NULL),
  ('3f160167-7dc9-4445-ae15-0b7fb976dbdc', 'cf8504aa-40f1-4019-b314-096e19b93a9f', 'SLAIF', 'production-3f160167', $q$https://www.slaif.si/events$q$, $q$Razvoj lastnih UI modelov$q$, $q$Izvajalec / Course provider: Academic and research network of Slovenia (Arnes)
Predavatelji / Instructors: Andraž Pustoslemšek (Arnes)

Learning objectives: Recognizing opportunities for developing your own AI model, understanding project data requirements, knowledge for designing project evaluation, and model implementation and management.$q$, $q$https://indico.ijs.si/event/3999/$q$, date_trunc('day', now()) + interval '20 days 09 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '20 days 10 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Online$q$, NULL, NULL),
  ('48ab5231-54ac-4128-b389-0a659e3da9d5', 'cf8504aa-40f1-4019-b314-096e19b93a9f', 'SLAIF', 'production-48ab5231', $q$https://www.slaif.si/events$q$, $q$Scaling AI with Multi-GPU$q$, $q$Izvajalec / Course provider: Faculty of information studies in Novo mesto (FIŠ)
Predavatelji / Instructors: Robi Pritržnik, Pavle Boškoski, Miloš Ivanović, Srdjan Škrbić, Biljana Miljeva Boshkoska

Scaling AI with Multi-GPU

This intensive 8-hour course (delivered online, in two 4-hour sessions) looks at Multi-GPU orchestration and Distributed Data Parallel (DDP) for AI work on the HPC Trdina supercomputer.$q$, $q$https://indico.ijs.si/event/3993/$q$, date_trunc('day', now()) + interval '51 days 23 hours 00 minutes 0 seconds', NULL, FALSE, FALSE, $q$Fakulteta za informacijske študije, Univerza v Novem mestu$q$, $q$Ljubljanska cesta 31a, 8000 Novo mesto$q$, NULL),
  ('035087b2-4c8f-455f-8767-bde6d37a3a5b', 'cf8504aa-40f1-4019-b314-096e19b93a9f', 'SLAIF', 'production-035087b2', $q$https://www.slaif.si/events$q$, $q$Vzpostavljanje podatkovne kulture: zbiranje, upravljanje in hramba podatkov$q$, $q$Izvajalec / Course provider: Academic and research network of Slovenia (Arnes)
Predavatelji / Instructors: Marko Drobnjak (Arnes)

Learning objectives: Planning the data life cycle, improving documentation, and preparing to establish a data storage and sharing plan within the organization.$q$, $q$https://indico.ijs.si/event/3989/$q$, date_trunc('day', now()) + interval '54 days 12 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '54 days 14 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Online$q$, NULL, NULL),
  ('f1b2a602-98f3-4534-99d8-4395dae67560', '1ef92e60-b197-410e-97d9-da8e77826225', 'STARTUP_SI', 'production-f1b2a602', $q$https://www.startup.si/sl-si/dogodki$q$, $q$PODIM 2027$q$, $q$Ste pripravljeni na največji startup in tehnološki dogodek v srednji in vzhodni Evropi? Dogodek je ukrojen po meri startup in scaleup podjetij, investitorjev, predstavnikov že uveljavljenih podjetij in vseh podjetniških ter tehnoloških navdušencev, ki želite biti v središču dogajanja.

PODIM 2027

Lokacija

PODIM se bo odvijal v centru Maribora, na dveh prizoriščih, ki sta med seboj oddaljeni le minuto hoje.$q$, $q$https://www.startup.si/sl-si/dogodek/podim-2027$q$, date_trunc('day', now()) + interval '171 days 12 hours 00 minutes 0 seconds', date_trunc('day', now()) + interval '173 days 13 hours 00 minutes 0 seconds', TRUE, TRUE, $q$Maribox$q$, $q$Loška ulica 13, 2000 Maribor$q$, NULL);

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

-- The YouTube channel as a look at it would have left it (a preview has no token, so it never looks): the released
-- episodes' videos, two Shorts, a stream, and a private upload that's still to be published; and how uploads went
INSERT INTO youtube_videos (id, video_id, channel_id, title, description, tags, privacy, upload_status, publish_at,
                            published_at, duration, live, episode_id, episode_manual) VALUES
  ('7b000000-0000-4000-8000-000000000001', 'example0001', 'UCpreview', 'Kako začeti z zagonskim podjetjem',
   'Peter pove, kako je začel, kaj bi danes naredil drugače in zakaj je prvi produkt zavrgel.', '{ogrodje,startup}',
   'PUBLIC', 'processed', NULL, now() - interval '180 days', 5412, false, 'e0000000-0000-4000-8000-000000000001', false),
  ('7b000000-0000-4000-8000-000000000002', 'example0002', 'UCpreview', 'Porazdeljeni sistemi brez bolečin',
   'Ana razloži, kaj je konsenz, zakaj so ure lažnive in kdaj potrebujete Raft.', '{ogrodje,"porazdeljeni sistemi"}',
   'PUBLIC', 'processed', NULL, now() - interval '100 days', 6120, false, 'e0000000-0000-4000-8000-000000000002', false),
  ('7b000000-0000-4000-8000-000000000003', 'example0003', 'UCpreview', 'Zakaj so ure lažnive #ogrodje', '', '{}',
   'PUBLIC', 'processed', NULL, now() - interval '95 days', 38, false, NULL, false),
  ('7b000000-0000-4000-8000-000000000004', 'example0004', 'UCpreview', 'Prvi produkt smo zavrgli #ogrodje', '', '{}',
   'PUBLIC', 'processed', NULL, now() - interval '170 days', 24, false, NULL, false),
  ('7b000000-0000-4000-8000-000000000005', 'example0005', 'UCpreview', 'Ogrodje v živo: vprašanja in odgovori', '', '{}',
   'UNLISTED', 'processed', NULL, now() - interval '60 days', 7360, true, NULL, false),
  ('7b000000-0000-4000-8000-000000000006', 'example0006', 'UCpreview', 'Platform teams that people like',
   'Sara on building an internal platform that teams choose rather than put up with.', '{}',
   'PRIVATE', 'processed', now() + interval '3 days', now() - interval '2 days', 4480, false, 'e0000000-0000-4000-8000-000000000003', false);

INSERT INTO youtube_uploads (id, episode_id, media_id, media_name, title, status, percent, error, video_id, workflow_id,
                             requester, created_at, started_at, finished_at) VALUES
  ('7c000000-0000-4000-8000-000000000001', 'e0000000-0000-4000-8000-000000000003', '7d000000-0000-4000-8000-000000000001',
   'S02E01.mp4', 'Platform teams that people like', 'DONE', NULL, NULL, 'example0006',
   'youtube-upload-7d000000-0000-4000-8000-000000000001', 'dev', now() - interval '2 days', now() - interval '2 days', now() - interval '2 days' + interval '14 minutes'),
  ('7c000000-0000-4000-8000-000000000002', 'e0000000-0000-4000-8000-000000000002', '7d000000-0000-4000-8000-000000000002',
   'S01E02 BONUS.mp4', 'Konsenz za vsakogar (bonus)', 'FAILED', NULL, 'YouTube turned it down as a duplicate of a video on the channel', NULL,
   'youtube-upload-7d000000-0000-4000-8000-000000000002', 'dev', now() - interval '5 days', now() - interval '5 days', now() - interval '5 days' + interval '3 minutes');

COMMIT;
