import type { DateLocale } from '@/i18n'
import { TIME_ZONE } from '@/i18n'

// Slovenia's tech, developer and startup events, as MetaOS's scrapers gather them from organisations' links, and the
// team adds them (Events.kt), as ogrodje.si's Dogodki page listed them when Goo gathered them: by week, Monday to Sunday,
// then by day, in Ljubljana's time, whatever the browser's. Their dates are in the page's language: in Slovenian as
// ogrodje.si wrote them (9. 11. – 15. 11. 2026), or in English (9 – 15 Nov 2026). The team also hides them, picks them
// (promoted), adds their own (MANUAL) and changes those wholly.

/** Who an event is by, as its badge: the organisation's name, logo and colours, the text's (mainColor) and its badge's */
export interface Meetup {
  name: string
  logo: string | null
  mainColor: string | null
  backgroundColor: string | null
}

/** An event: when it starts and ends (instants), with a time of day only where hasStartTime and hasEndTime say */
export interface ListedEvent {
  id: string
  title: string
  description?: string | null
  url: string | null
  start: string
  end: string | null
  hasStartTime: boolean
  hasEndTime: boolean
  locationName: string | null
  locationAddress: string | null
  /** Where it is: its own location, else its organisation's venue (fromOrganisation) */
  place?: EventPlace | null
  promoted: boolean
  meetup: Meetup | null
}

/** Where an event is: its place and address, either may be missing */
export interface EventPlace {
  name: string | null
  address: string | null
  fromOrganisation: boolean
}

/** Which scraper found an event, or MANUAL for the team's own */
export type EventSource = 'MANUAL' | string

/** An event as the team sees it: whether it's hidden, who found it where, and whose it is */
export interface TeamEvent extends ListedEvent {
  description: string | null
  hidden: boolean
  source: EventSource
  sourceUrl: string | null
  organisation: { id: string, name: string }
}

export const EVENT_FIELDS = `id title url start end hasStartTime hasEndTime locationName locationAddress promoted
  place { name address fromOrganisation } meetup { name logo mainColor backgroundColor }`

/** An event's fields for the team, on top of everyone's */
export const TEAM_EVENT_FIELDS = `${EVENT_FIELDS} description hidden source sourceUrl organisation { id name }`

/** Whether the team may change [event] wholly: their own; one a scraper found only hides or is picked */
export const isManual = (event: Pick<TeamEvent, 'source'>) => event.source === 'MANUAL'

/** The team's view of the upcoming events: the hidden ones too */
export const TEAM_EVENTS = `{ events(hidden: true) { ${TEAM_EVENT_FIELDS} } }`

/** Whether a refresh of the events, every organisation's or one's, is on */
export interface EventsRefresh {
  running: boolean
  startedAt: string | null
}

export const EVENTS_REFRESH = 'query ($organisation: ID) { refresh: eventsRefresh(organisation: $organisation) { running startedAt } }'
export const REFRESH_EVENTS = 'mutation ($organisation: ID) { refresh: refreshEvents(organisation: $organisation) { running startedAt } }'

/** What an event is to be, as EventInput has it: what's left out stays as it is, and empty text clears it */
export interface EventInput {
  organisation?: string
  title?: string
  start?: string
  end?: string
  description?: string
  url?: string
  locationName?: string
  locationAddress?: string
  hidden?: boolean
  promoted?: boolean
}

export const CREATE_EVENT = `mutation ($input: EventInput!) { event: createEvent(input: $input) { ${TEAM_EVENT_FIELDS} } }`
export const UPDATE_EVENT = `mutation ($id: ID!, $input: EventInput!) { event: updateEvent(id: $id, input: $input) { ${TEAM_EVENT_FIELDS} } }`

// A day in Ljubljana, as YYYY-MM-DD, e.g. for an event that starts at 23:30 UTC, the next day's
const dayFormat = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: TIME_ZONE })
export const dayOf = (at: string) => dayFormat.format(new Date(at))

// A day (YYYY-MM-DD) as a date at midnight UTC, to count days on without time zones
const utc = (day: string) => new Date(`${day}T00:00:00Z`)
const dayString = (date: Date) => date.toISOString().slice(0, 10)

/** The Monday of [day]'s week */
export function weekOf(day: string) {
  const date = utc(day)
  date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7))
  return dayString(date)
}

const capitalized = (text: string, dateLocale: DateLocale) => text.charAt(0).toLocaleUpperCase(dateLocale) + text.slice(1)

/** [day]'s name, e.g. Torek, or Tuesday */
export function weekdayName(day: string, dateLocale: DateLocale) {
  return capitalized(utc(day).toLocaleDateString(dateLocale, { weekday: 'long', timeZone: 'UTC' }), dateLocale)
}

// e.g. 9. 11., as ogrodje.si wrote it, rather than Intl's 9.–15. 11.
const dayAndMonth = (date: Date) => `${date.getUTCDate()}. ${date.getUTCMonth() + 1}.`

/** A week by its Monday, e.g. 9. 11. – 15. 11. 2026, or 9 – 15 Nov 2026 */
export function weekRange(monday: string, dateLocale: DateLocale) {
  const sunday = utc(monday)
  sunday.setUTCDate(sunday.getUTCDate() + 6)
  if (dateLocale === 'sl-SI') return `${dayAndMonth(utc(monday))} – ${dayAndMonth(sunday)} ${sunday.getUTCFullYear()}`
  return new Intl.DateTimeFormat(dateLocale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
    .formatRange(utc(monday), sunday)
}

/** [day] as a short date, e.g. 10. 11., or 10 Nov */
export function shortDay(day: string, dateLocale: DateLocale) {
  if (dateLocale === 'sl-SI') return dayAndMonth(utc(day))
  return utc(day).toLocaleDateString(dateLocale, { day: 'numeric', month: 'short', timeZone: 'UTC' })
}

const date = (at: string, dateLocale: DateLocale) =>
  new Date(at).toLocaleDateString(dateLocale, { dateStyle: 'medium', timeZone: TIME_ZONE })
const time = (at: string, dateLocale: DateLocale) =>
  new Date(at).toLocaleTimeString(dateLocale, { hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })

/**
 * When an event is, as ogrodje.si said it: its start's date, with its time where it has one, and its end's time, with
 * its date when it's another day's, e.g. 10. nov. 2026 11:00 – 12:00, or 11. nov. 2026 15:00 – 25. nov. 2026 17:30. An
 * end without a time shows only when it's another day, as its date, e.g. 23. nov. 2026 – 25. nov. 2026; an end the
 * same as the start doesn't. Unlike ogrodje.si, which took every time for summer time's (UTC+2), in Ljubljana's time.
 * In English, e.g. 10 Nov 2026 11:00 – 12:00.
 */
export function eventTime(event: Pick<ListedEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>, dateLocale: DateLocale) {
  const day = (at: string) => date(at, dateLocale)
  const hour = (at: string) => time(at, dateLocale)
  const start = event.hasStartTime ? `${day(event.start)} ${hour(event.start)}` : day(event.start)
  const { end } = event
  if (!end || end === event.start) return start
  const sameDay = dayOf(end) === dayOf(event.start)
  if (event.hasEndTime) return `${start} – ${sameDay ? hour(end) : `${day(end)} ${hour(end)}`}`
  return sameDay ? start : `${start} – ${day(end)}`
}

/** A colour of its own for a meetup without one, from its name, the same everywhere */
export function colorOf(name: string) {
  let hash = 0
  for (const char of name) hash = (hash * 31 + char.codePointAt(0)!) | 0
  return `hsl(${Math.abs(hash) % 360} 45% 36%)`
}

/** A meetup's badge: its own colours, or white on a colour from its name where it has none */
export function badgeColors(meetup: Pick<Meetup, 'name' | 'mainColor' | 'backgroundColor'>) {
  if (meetup.mainColor && meetup.backgroundColor) return { color: meetup.mainColor, background: meetup.backgroundColor }
  return { color: meetup.mainColor ?? '#FFFFFF', background: meetup.backgroundColor ?? colorOf(meetup.name) }
}

export interface EventDay<E> { day: string, events: E[] }
export interface EventWeek<E> { monday: string, days: EventDay<E>[], count: number }

/** [events], the soonest first, by the week they start in, then by the day */
export function byWeek<E extends Pick<ListedEvent, 'start'>>(events: E[]): EventWeek<E>[] {
  const weeks = new Map<string, Map<string, E[]>>()
  for (const event of [...events].sort((a, b) => a.start.localeCompare(b.start))) {
    const day = dayOf(event.start)
    const days = weeks.get(weekOf(day)) ?? new Map<string, E[]>()
    weeks.set(weekOf(day), days.set(day, [...(days.get(day) ?? []), event]))
  }
  return [...weeks].map(([monday, days]) => ({
    monday,
    days: [...days].map(([day, events]) => ({ day, events })),
    count: [...days.values()].reduce((sum, events) => sum + events.length, 0),
  }))
}

/** When an event starts or ends, as its form has it: a day (YYYY-MM-DD) and a time in Ljubljana (HH:mm), or none */
export interface FormTime {
  day: string
  time: string
}

const timeFormat = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: TIME_ZONE })

/** [at], an instant, as the form shows it: its day in Ljubljana, and its time there where it has one */
export function formTime(at: string | null, hasTime: boolean): FormTime {
  if (!at) return { day: '', time: '' }
  return { day: dayOf(at), time: hasTime ? timeFormat.format(new Date(at)) : '' }
}

/**
 * What the form says, as EventInput takes it: a time in Ljubljana, e.g. 2026-11-16T18:00, or a day, 2026-11-16, without
 * one; empty without a day
 */
export function inputTime(value: FormTime) {
  const day = value.day.trim()
  if (!day) return ''
  const time = value.time.trim()
  return time ? `${day}T${time}` : day
}

// Scrapers' names as their sites write them; any other's from its code, e.g. NEW_SCRAPER, New Scraper
const SOURCE_NAMES: Record<string, string> = {
  MEETUP: 'Meetup', EVENTBRITE: 'Eventbrite', ICAL: 'iCal', LUMA: 'Luma', KOMPOT: 'Kompot', WEBSITE: 'Website',
  TEHNOLOSKI_PARK_LJUBLJANA: 'Tehnološki park Ljubljana', PRIMORSKI_TEHNOLOSKI_PARK: 'Primorski tehnološki park',
  POMURSKI_TEHNOLOSKI_PARK: 'Pomurski tehnološki park', STARTUP_SI: 'Startup.si', GZS: 'GZS', FRI: 'FRI', KCUI: 'KCUI',
  LUI: 'LUI', SLAIF: 'SLAIF', MPIK_KOROSKA: 'MPIK Koroška', DATA_SCIENCE: 'Data Science', KATAPULT: 'Katapult',
  INKUBATOR_SAVINJSKE_REGIJE: 'Inkubator Savinjske regije', RACUNALNISKI_MUZEJ: 'Računalniški muzej',
  SPLETNE_URICE: 'Spletne urice', INKUBATOR_BELA_KRAJINA: 'Inkubator Bela krajina',
  INKUBATOR_ROGASKA_SLATINA: 'Inkubator Rogaška Slatina', PODJETNISKI_INKUBATOR_PODBREZNIK: 'Podjetniški inkubator Podbreznik',
}

/** Which scraper found an event, or read an organisation's link, as people say it, e.g. Meetup */
export function sourceName(source: string) {
  return SOURCE_NAMES[source] ?? source.split('_').map((word) => word.charAt(0) + word.slice(1).toLowerCase()).join(' ')
}

// A place that isn't one, which no map has
const ONLINE = /\b(online|on-line|splet\w*|virtual\w*|virtualn\w*|zoom|teams|youtube|discord|webinar\w*)\b/i

/** Where an event is, on a line: its place and its address, or either; the place once when they're the same */
export function placeLine(place: Pick<EventPlace, 'name' | 'address'> | null | undefined): string | null {
  const name = place?.name?.trim() || null
  const address = place?.address?.trim() || null
  if (!name || !address) return name ?? address
  const lower = (text: string) => text.toLocaleLowerCase('sl')
  if (lower(address).includes(lower(name))) return address
  if (lower(name).includes(lower(address))) return name
  return `${name}, ${address}`
}

/** Whether an event's place is online, e.g. Zoom or Spletni dogodek, so it has no map */
export const isOnline = (place: Pick<EventPlace, 'name' | 'address'> | null | undefined) =>
  !!place && !place.address && !!place.name && ONLINE.test(place.name)

/** A link to the place on a map (Google Maps' search, which opens the app on phones), or none for one online */
export function mapUrl(place: Pick<EventPlace, 'name' | 'address'> | null | undefined): string | null {
  const line = placeLine(place)
  if (!line || isOnline(place)) return null
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(line)}`
}
