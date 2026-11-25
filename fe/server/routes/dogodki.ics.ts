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

type Listed = CalendarEvent & { region: Region | null, promoted: boolean }

const QUERY = `{ events { id title description url start end hasStartTime hasEndTime region promoted
  place { name address } meetup { name } } }`

const one = (value: unknown) => (Array.isArray(value) ? value[0] : value)

export default defineEventHandler(async (event) => {
  const query = getQuery(event)
  const region = String(one(query.region) ?? ALL_REGIONS)
  const show = String(one(query.show) ?? 'all') as Shown
  // A region or a choice there isn't would be an empty calendar for good, with nothing to say why
  if (region !== ALL_REGIONS && !regionOf(region, REGIONS)) throw createError({ statusCode: 400, statusMessage: 'No such region' })
  if (!SHOWN.includes(show)) throw createError({ statusCode: 400, statusMessage: 'show is all or promoted' })

  const { metaosUrl, public: { siteUrl } } = useRuntimeConfig(event)
  const response = await fetch(`${metaosUrl}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ query: QUERY }),
    signal: AbortSignal.timeout(10_000),
  }).catch(() => null)
  const { data, errors } = response?.ok ? await response.json() as { data?: { events: Listed[] }, errors?: unknown[] } : {}
  if (!data || errors?.length) throw createError({ statusCode: 502, statusMessage: "MetaOS didn't list its events" })

  const events = data.events.filter((each) => inRegion(each, region) && (show === 'all' || each.promoted))
  const name = calendarName(regionOf(region, REGIONS), show, (each) => sl.events.regions[each])
  setHeader(event, 'content-type', 'text/calendar; charset=utf-8')
  setHeader(event, 'content-disposition', 'inline; filename="ogrodje-dogodki.ics"')
  if (!import.meta.dev) setHeader(event, 'cache-control', 'public, s-maxage=900, stale-while-revalidate=3600')
  return calendar(events, { name, siteUrl, now: new Date() })
})
