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

/**
 * When an event is, as DTSTART and DTEND. One with a time starts at that instant, and ends at its end's where that has
 * a time and is later: an end nobody gave isn't made up. One without a time is its days in Ljubljana, whole, to its
 * end's day, as the Events page says them (eventTime), which DTEND, the day after the last, has one later: the team's
 * own end at 23:59 of their last day and most scrapers' at some time of it. So is one that starts at a time and ends
 * days later without one, as calendars have no start at a time with an end on a day.
 */
export function eventTimes(event: Pick<CalendarEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>): string[] {
  const firstDay = dayOf(event.start)
  const endDay = event.end ? dayOf(event.end) : firstDay
  const lastDay = endDay > firstDay ? endDay : firstDay
  if (!event.hasStartTime || (!event.hasEndTime && lastDay > firstDay)) {
    return [`DTSTART;VALUE=DATE:${date(firstDay)}`, `DTEND;VALUE=DATE:${date(nextDay(lastDay))}`]
  }
  const start = new Date(event.start)
  const end = event.end && event.hasEndTime ? new Date(event.end) : null
  return [`DTSTART:${instant(start)}`, ...(end && end > start ? [`DTEND:${instant(end)}`] : [])]
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
