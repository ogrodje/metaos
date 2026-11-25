import { dayOf, eventPath, type EventPlace, nextDay, placeLine, type Region, regionSlug, type Shown } from '@/events'
import { TIME_ZONE } from '@/i18n'

// The events as a calendar to subscribe to (iCalendar, RFC 5545), which /dogodki.ics serves (server/routes): what
// Google Calendar, Apple's and Outlook read every few hours, so the upcoming events are in people's own calendars.
// Written by hand, as it's a few lines an event. In Slovenian only, as the events are. No Nuxt in it, as the unit tests
// and the server's route load it.

/** An event as the calendar has it: when it is, where, who it's by and where it's announced */
export interface CalendarEvent {
  id: string
  title: string
  description?: string | null
  url: string | null
  start: string
  end: string | null
  hasStartTime: boolean
  hasEndTime: boolean
  place?: Pick<EventPlace, 'name' | 'address'> | null
  meetup?: { name: string } | null
}

/** What the calendar is: its name, as calendar apps list it, the site's address, for the events' pages, and when it's made */
export interface CalendarOptions {
  name: string
  siteUrl: string
  now: Date
}

/** The calendar's address, with the Events page's filters: /dogodki.ics?region=gorenjska&show=promoted */
export function calendarPath(region: string = 'all', show: Shown = 'all') {
  const query = new URLSearchParams()
  if (region !== 'all') query.set('region', region)
  if (show !== 'all') query.set('show', show)
  return `/dogodki.ics${query.size ? `?${query}` : ''}`
}

/** [url], http or https, as calendar apps subscribe to it: webcal://ogrodje.si/dogodki.ics */
export const webcalUrl = (url: string) => url.replace(/^https?:/, 'webcal:')

/** Where Google Calendar asks to add [url] as a calendar of its own: on the web and on Android, webcal isn't its */
export const googleCalendarUrl = (url: string) =>
  `https://calendar.google.com/calendar/render?cid=${encodeURIComponent(webcalUrl(url))}`

/**
 * Where Outlook on the web asks to subscribe to [url], as a calendar by [name]: Microsoft 365's, for work and school
 * accounts (office), or Outlook.com's, for personal ones (live), as each signs in only its own
 */
export const outlookCalendarUrl = (url: string, name: string, host: 'office' | 'live') =>
  `https://outlook.${host}.com/calendar/0/addfromweb?url=${encodeURIComponent(url)}&name=${encodeURIComponent(name)}`

/** The calendar's name, with what it's filtered to: Ogrodje – dogodki (Gorenjska, izpostavljeni) */
export function calendarName(region: Region | null, show: Shown, regionName: (region: Region) => string) {
  const filters = [region ? regionName(region) : null, show === 'promoted' ? 'izpostavljeni' : null].filter(Boolean)
  return `Ogrodje – dogodki${filters.length ? ` (${filters.join(', ')})` : ''}`
}

/** The region a slug of the address is, e.g. primorsko-notranjska; none for one there isn't */
export const regionOf = (slug: string, regions: readonly Region[]) => regions.find((region) => regionSlug(region) === slug) ?? null

/** Text as a property's value: its backslashes, semicolons, commas and line breaks escaped, no other control characters */
export function escapeText(text: string) {
  return text
    .replace(/\r\n?/g, '\n')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0009\u000B-\u001F\u007F]/g, '')
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\n/g, '\\n')
}

const encoder = new TextEncoder()

// How long a line may be, in octets, without its line break
const LINE_OCTETS = 75

/**
 * A line as the file has it: at most 75 octets, not characters, the rest on lines that start with a space, never cut
 * inside a character (č is two octets, an emoji four)
 */
export function fold(line: string) {
  const lines: string[] = []
  let current = ''
  let octets = 0
  for (const char of line) {
    const size = encoder.encode(char).length
    if (octets + size > LINE_OCTETS) {
      lines.push(current)
      current = ' '
      octets = 1
    }
    current += char
    octets += size
  }
  lines.push(current)
  return lines.join('\r\n')
}

// An instant in UTC, e.g. 20261110T170000Z
const instant = (at: Date) => `${at.toISOString().slice(0, 19).replace(/[-:]/g, '')}Z`

// A day (YYYY-MM-DD), e.g. 20261110
const date = (day: string) => day.replace(/-/g, '')

type Timed = Pick<CalendarEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>

/** When an event is: its days in Ljubljana, whole, to the day after its last, or its instants, with no end nobody gave */
export type EventSpan = { allDay: true, firstDay: string, dayAfter: string } | { allDay: false, start: Date, end: Date | null }

/**
 * When an event is, for the calendar and for the links that add it to one. One with a time starts at that instant, and
 * ends at its end's where that has a time and is later: an end nobody gave isn't made up. One without a time is its
 * days in Ljubljana, whole, to its end's day, as the Events page says them (eventTime): the team's own end at 23:59 of
 * their last day and most scrapers' at some time of it. So is one that starts at a time and ends days later without
 * one, as calendars have no start at a time with an end on a day.
 */
export function eventSpan(event: Timed): EventSpan {
  const firstDay = dayOf(event.start)
  const endDay = event.end ? dayOf(event.end) : firstDay
  const lastDay = endDay > firstDay ? endDay : firstDay
  if (!event.hasStartTime || (!event.hasEndTime && lastDay > firstDay)) return { allDay: true, firstDay, dayAfter: nextDay(lastDay) }
  const start = new Date(event.start)
  const end = event.end && event.hasEndTime ? new Date(event.end) : null
  return { allDay: false, start, end: end && end > start ? end : null }
}

/** When an event is, as DTSTART and DTEND (eventSpan); whole days end the day after their last, as DTEND has them */
export function eventTimes(event: Timed): string[] {
  const span = eventSpan(event)
  if (span.allDay) return [`DTSTART;VALUE=DATE:${date(span.firstDay)}`, `DTEND;VALUE=DATE:${date(span.dayAfter)}`]
  return [`DTSTART:${instant(span.start)}`, ...(span.end ? [`DTEND:${instant(span.end)}`] : [])]
}

/** What an event's description says: who it's by, what its organiser wrote of it, and where it's announced */
export function eventDescription(event: Pick<CalendarEvent, 'description' | 'url' | 'meetup'>) {
  return [
    event.meetup?.name ? `Organizator: ${event.meetup.name}` : null,
    event.description?.trim() || null,
    event.url ? `Več o dogodku: ${event.url}` : null,
  ].filter(Boolean).join('\n\n')
}

function eventLines(event: CalendarEvent, { siteUrl, now }: CalendarOptions): string[] {
  const place = placeLine(event.place)
  const description = eventDescription(event)
  return [
    'BEGIN:VEVENT',
    // The event's ID in MetaOS, which stays, so one that changed is the same one in the calendar
    `UID:${event.id}@ogrodje.si`,
    `DTSTAMP:${instant(now)}`,
    ...eventTimes(event),
    `SUMMARY:${escapeText(event.title)}`,
    ...(place ? [`LOCATION:${escapeText(place)}`] : []),
    ...(description ? [`DESCRIPTION:${escapeText(description)}`] : []),
    `URL:${siteUrl}${eventPath(event)}`,
    'END:VEVENT',
  ]
}

/**
 * [events] as a calendar, the soonest first: each line folded, and ended as the format has it (CRLF). Its times are
 * instants in UTC, so it needs no time zones of its own; days are Ljubljana's. Apps are asked to read it again every
 * hour, as often as the scrapers look.
 */
export function calendar(events: CalendarEvent[], options: CalendarOptions) {
  const sorted = [...events].sort((a, b) => a.start.localeCompare(b.start))
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Ogrodje//MetaOS//SL',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    `X-WR-CALNAME:${escapeText(options.name)}`,
    `X-WR-TIMEZONE:${TIME_ZONE}`,
    'REFRESH-INTERVAL;VALUE=DURATION:PT1H',
    'X-PUBLISHED-TTL:PT1H',
    ...sorted.flatMap((event) => eventLines(event, options)),
    'END:VCALENDAR',
    '',
  ].map(fold).join('\r\n')
}

// An event alone, added to a calendar rather than subscribed to: Google's, Outlook's and Yahoo's own pages that make
// an event of what their address says, and a file of it (/dogodki.ics?event=), which the device's app opens.

// How much of a description an address carries: the rest is on the event's page
const LINK_DESCRIPTION = 1000

function linkDescription(event: Pick<CalendarEvent, 'description' | 'url' | 'meetup'>) {
  const text = eventDescription(event)
  return text.length > LINK_DESCRIPTION ? `${text.slice(0, LINK_DESCRIPTION - 1).trimEnd()}…` : text
}

// An address with what it asks: nothing for what there's none of
function withQuery(address: string, query: Record<string, string | null | undefined>) {
  const params = Object.entries(query).filter((each): each is [string, string] => !!each[1])
  return `${address}?${params.map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&')}`
}

/** Where Google Calendar makes an event of [event]; one with no end ends as it starts, as Google wants an end */
export function googleEventUrl(event: CalendarEvent) {
  const span = eventSpan(event)
  return withQuery('https://calendar.google.com/calendar/render', {
    action: 'TEMPLATE',
    text: event.title,
    dates: span.allDay ? `${date(span.firstDay)}/${date(span.dayAfter)}` : `${instant(span.start)}/${instant(span.end ?? span.start)}`,
    details: linkDescription(event),
    location: placeLine(event.place),
  })
}

/** Where Outlook on the web makes an event of [event]: Microsoft 365's (office) or Outlook.com's (live) */
export function outlookEventUrl(event: CalendarEvent, host: 'office' | 'live') {
  const span = eventSpan(event)
  const at = (instant: Date) => `${instant.toISOString().slice(0, 19)}Z`
  return withQuery(`https://outlook.${host}.com/calendar/0/deeplink/compose`, {
    path: '/calendar/action/compose',
    rru: 'addevent',
    subject: event.title,
    startdt: span.allDay ? span.firstDay : at(span.start),
    enddt: span.allDay ? span.dayAfter : at(span.end ?? span.start),
    allday: span.allDay ? 'true' : null,
    body: linkDescription(event),
    location: placeLine(event.place),
  })
}

/** Where Yahoo Calendar makes an event of [event] */
export function yahooEventUrl(event: CalendarEvent) {
  const span = eventSpan(event)
  return withQuery('https://calendar.yahoo.com/', {
    v: '60',
    title: event.title,
    st: span.allDay ? date(span.firstDay) : instant(span.start),
    et: span.allDay ? date(span.dayAfter) : instant(span.end ?? span.start),
    dur: span.allDay ? 'allday' : null,
    desc: linkDescription(event),
    in_loc: placeLine(event.place),
  })
}

/** An event alone as a calendar file, which calendar apps add rather than subscribe to: /dogodki.ics?event=<ID> */
export const eventFilePath = (event: Pick<CalendarEvent, 'id'>) => `/dogodki.ics?event=${encodeURIComponent(event.id)}`

/** A way into a calendar, as Add to calendar lists them (CalendarMenu.vue): which, its address, and whether that's another site */
export interface CalendarLink {
  kind: 'google' | 'outlook365' | 'outlookCom' | 'yahoo' | 'app' | 'file'
  href: string
  external: boolean
}

/** The ways to subscribe to the calendar at [url], by [name]: Google's, Outlook on the web's two, and the device's app */
export const subscribeLinks = (url: string, name: string): CalendarLink[] => [
  { kind: 'google', href: googleCalendarUrl(url), external: true },
  { kind: 'outlook365', href: outlookCalendarUrl(url, name, 'office'), external: true },
  { kind: 'outlookCom', href: outlookCalendarUrl(url, name, 'live'), external: true },
  { kind: 'app', href: webcalUrl(url), external: false },
]

/** The ways to add [event] alone to a calendar: Google's, Outlook on the web's two, Yahoo's, and its file */
export const eventLinks = (event: CalendarEvent, siteUrl: string): CalendarLink[] => [
  { kind: 'google', href: googleEventUrl(event), external: true },
  { kind: 'outlook365', href: outlookEventUrl(event, 'office'), external: true },
  { kind: 'outlookCom', href: outlookEventUrl(event, 'live'), external: true },
  { kind: 'yahoo', href: yahooEventUrl(event), external: true },
  { kind: 'file', href: siteUrl + eventFilePath(event), external: false },
]
