import { createError, defineEventHandler, getQuery, setHeader } from 'h3'
import { useRuntimeConfig } from 'nitropack/runtime'
import { calendar, type CalendarEvent, calendarName, regionOf } from '~/calendar'
import { ALL_REGIONS, inRegion, type Region, REGIONS, SHOWN, type Shown } from '~/events'
import sl from '~/locales/sl.json'

// The Events page as a calendar to subscribe to, in Google Calendar, Apple's or Outlook (calendar.ts): the upcoming
// events that aren't hidden, as MetaOS's API gives them to someone who isn't signed in, so the same for everyone, and
// filtered as the page is, by its address: /dogodki.ics?region=gorenjska&show=promoted. Kept by Cloudflare for a quarter
// of an hour, then served while it's made again, as the pages are (nuxt.config.ts), not by Nitro; the scrapers look
// every hour, and calendar apps ask every few hours at best.
//
// With ?event=<ID>, that event alone, a past one too, as a file a calendar app adds rather than subscribes to, which
// an event's page offers; a hidden one isn't there, as for anyone not signed in.

type Listed = CalendarEvent & { region: Region | null, promoted: boolean }

const FIELDS = 'id title description url start end hasStartTime hasEndTime place { name address } meetup { name }'
const QUERY = `{ events { ${FIELDS} region promoted } }`
const EVENT_QUERY = `query ($id: ID!) { event(id: $id) { ${FIELDS} } }`

// MetaOS's IDs, so anything else isn't asked for
const ID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i

const one = (value: unknown) => (Array.isArray(value) ? value[0] : value)

// What MetaOS answers [query], as to someone not signed in
async function ask<T>(metaosUrl: string, query: string, variables?: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${metaosUrl}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ query, variables }),
    signal: AbortSignal.timeout(10_000),
  }).catch(() => null)
  const { data, errors } = response?.ok ? await response.json() as { data?: T, errors?: unknown[] } : {}
  if (!data || errors?.length) throw createError({ statusCode: 502, statusMessage: "MetaOS didn't list its events" })
  return data
}

export default defineEventHandler(async (event) => {
  const query = getQuery(event)
  const { metaosUrl, public: { siteUrl } } = useRuntimeConfig(event)
  const cached = () => {
    setHeader(event, 'content-type', 'text/calendar; charset=utf-8')
    if (!import.meta.dev) setHeader(event, 'cache-control', 'public, s-maxage=900, stale-while-revalidate=3600')
  }

  const id = one(query.event)
  if (id !== undefined) {
    if (query.region !== undefined || query.show !== undefined) throw createError({ statusCode: 400, statusMessage: 'An event alone has no filters' })
    const found = ID.test(String(id)) ? (await ask<{ event: CalendarEvent | null }>(metaosUrl, EVENT_QUERY, { id })).event : null
    if (!found) throw createError({ statusCode: 404, statusMessage: 'No such event' })
    cached()
    setHeader(event, 'content-disposition', 'attachment; filename="ogrodje-dogodek.ics"')
    return calendar([found], { name: found.title, siteUrl, now: new Date() })
  }

  const region = String(one(query.region) ?? ALL_REGIONS)
  const show = String(one(query.show) ?? 'all') as Shown
  // A region or a choice there isn't would be an empty calendar for good, with nothing to say why
  if (region !== ALL_REGIONS && !regionOf(region, REGIONS)) throw createError({ statusCode: 400, statusMessage: 'No such region' })
  if (!SHOWN.includes(show)) throw createError({ statusCode: 400, statusMessage: 'show is all or promoted' })

  const data = await ask<{ events: Listed[] }>(metaosUrl, QUERY)
  const events = data.events.filter((each) => inRegion(each, region) && (show === 'all' || each.promoted))
  const name = calendarName(regionOf(region, REGIONS), show, (each) => sl.events.regions[each])
  cached()
  setHeader(event, 'content-disposition', 'inline; filename="ogrodje-dogodki.ics"')
  return calendar(events, { name, siteUrl, now: new Date() })
})
