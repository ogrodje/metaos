# scrapers

The scrapers of Goo's sources (github.com/ogrodje/goo), ported to Kotlin: they read the events of the organisations
that gather them, from their websites, Meetup, Eventbrite, iCal calendars, Luma and the rest, for MetaOS's Events page
(`/dogodki`). A service of its own, so Chromium and sites that change their pages stay out of MetaOS.

Kotlin and Spring Boot like MetaOS, with jsoup, ical4j and Playwright for Java; a Gradle build of its own
(`./gradlew -p scrapers ...`).

## How it works

- It's a Temporal worker on task queue `scrapers` (`Activities.kt`), with one activity, `ScrapeEvents`: an
  organisation's link (which field it's in, its URL, and since when) in, the events found there out. MetaOS has the
  same interface by the same name (`EventsSync.kt`), so changing one is changing both. Its `EventsSyncWorkflow` asks for
  each link of every organisation that gathers events, every hour and when the team presses Refresh, and keeps what's
  found; the scrapers keep nothing, have no database and no API.
- A link goes to the parser that takes it, by its field and host, in Goo's order (`Parsers.kt`): Meetup, Eventbrite,
  iCal and Luma by their fields, the rest by the website's host (Tehnološki park Ljubljana, GZS, Primorski tehnološki
  park, Startup.si, DataScience, FRI, KCUI, the incubators, Računalniški muzej, LUI, Spletne urice, SLAIF, Katapult,
  Podbreznik, MPIK). A link no parser takes answers no events, which MetaOS shows as such.
- Where an event is, its venue's name and its address ("Kovačnica", "Poštna ulica 4, 4000 Kranj"), or "Online", is
  made alike for every source by `Locations.kt`: from structured data where a source has it (Meetup's and Eventbrite's
  venues, Luma's Google places, JSON-LD's `Place`, The Events Calendar's and Indico's APIs), else split from the one
  line it writes (iCal's LOCATION, "Kje: …"). What a source doesn't say stays empty.
- Events that ended over a year ago are left out (`since`). An event keeps Goo's ID for it (`externalId`, e.g.
  `meetup-123`), so the events MetaOS imported from Goo once (OG-64) are the ones found again.
- Most read the sites over HTTP (`Web.kt`); Eventbrite's and GZS's pages need a browser, Playwright's browser server in
  a container beside it in the pod (`SCRAPERS_BROWSER_URL`, `Browser.kt`), else a Chromium it launches. One page at a
  time.
- What fails as the site's fault (5xx, 429, a timeout) Temporal asks again; a 4xx or a link that isn't one, it doesn't
  (`ScraperActivitiesImpl`), and MetaOS says why on the organisation's page.

## Running it

```bash
./gradlew -p scrapers test                                          # parsers against saved pages, no web
./gradlew -p scrapers scrape --args='https://www.meetup.com/js-ljubljana/ MEETUP'   # one link, live
TEMPORAL_ADDRESS=localhost:7233 ./gradlew -p scrapers bootRun      # the worker, against compose's Temporal
```

In production it's `deployment-scrapers.yaml`, one pod, which CI tests, builds and rolls out with the rest
(`docker/scrapers.Dockerfile`); `just scrapers-logs` follows it.

## A new source

A `Parser` in `parsers/` (its `Source`, which links it `accepts`, and `parse`), in `PARSERS`, with a test against a
saved page in `src/test/resources`. MetaOS takes any source's name, so it needs no change.
