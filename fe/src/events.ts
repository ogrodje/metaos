import type { DateLocale, Translate } from '@/i18n'
import { TIME_ZONE } from '@/i18n'
import { type Image, IMAGE_FIELDS } from '@/images'

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
  /** Where that is, for the page's filter: its own region, else its organisation's; none where nothing says */
  region?: Region | null
  promoted: boolean
  meetup: Meetup | null
}

/** Where an event is: its place and address, either may be missing */
export interface EventPlace {
  name: string | null
  address: string | null
  fromOrganisation: boolean
}

/**
 * Where an event is, for the Events page's filter (Regions.kt): Slovenia's 12 statistical regions, in the order
 * Wikipedia lists them, then online and abroad
 */
export const REGIONS = [
  'POMURSKA', 'PODRAVSKA', 'KOROSKA', 'SAVINJSKA', 'ZASAVSKA', 'POSAVSKA', 'JUGOVZHODNA', 'OSREDNJESLOVENSKA', 'GORENJSKA',
  'PRIMORSKO_NOTRANJSKA', 'GORISKA', 'OBALNO_KRASKA', 'ONLINE', 'ABROAD',
] as const

export type Region = typeof REGIONS[number]

/** A region's name in the page's language: the regions' are Slovenian in both, e.g. Gorenjska */
export const regionName = (region: Region, t: Translate) => t(`events.regions.${region}`)

/** A region as the address has it, e.g. ?region=primorsko-notranjska */
export const regionSlug = (region: Region) => region.toLowerCase().replace(/_/g, '-')

/** What the filter shows every event by */
export const ALL_REGIONS = 'all'

/**
 * The filter's choices: every region, then those of [events], each with how many are in it, in the regions' own order;
 * those with none are left out, but the one the address says ([selected]), as are the events nothing tells the region
 * of, which only every region shows
 */
export function regionOptions(
  events: Pick<ListedEvent, 'region'>[], t: Translate, selected: string = ALL_REGIONS,
): { value: string, title: string, count?: number }[] {
  const counts = new Map<Region, number>()
  for (const event of events) if (event.region) counts.set(event.region, (counts.get(event.region) ?? 0) + 1)
  return [
    { value: ALL_REGIONS, title: t('events.regionAll') },
    ...REGIONS.filter((region) => counts.has(region) || regionSlug(region) === selected)
      .map((region) => ({ value: regionSlug(region), title: regionName(region, t), count: counts.get(region) ?? 0 })),
  ]
}

/** Whether [event] is in the region the address says ([slug]); every one is in all of them */
export const inRegion = (event: Pick<ListedEvent, 'region'>, slug: string) =>
  slug === ALL_REGIONS || (!!event.region && regionSlug(event.region) === slug)

/** Which events the page shows: all of them, or Ogrodje's picks alone (?show=promoted) */
export const SHOWN = ['all', 'promoted'] as const
export type Shown = typeof SHOWN[number]

/** The chips for it, each with how many of [events] it shows */
export function shownOptions(events: Pick<ListedEvent, 'promoted'>[], t: Translate): { value: Shown, title: string, count: number }[] {
  return [
    { value: 'all', title: t('events.showAll'), count: events.length },
    { value: 'promoted', title: t('events.showPromoted'), count: events.filter((event) => event.promoted).length },
  ]
}

/** What an event is found by in the list: its title, who it's by and where it is */
export function eventText(event: Pick<ListedEvent, 'title' | 'meetup' | 'place' | 'locationName' | 'locationAddress'>) {
  const place = event.place ?? { name: event.locationName, address: event.locationAddress }
  return [event.title, event.meetup?.name, place.name, place.address].filter(Boolean).join(' ')
}

/** Which scraper found an event, or MANUAL for the team's own */
export type EventSource = 'MANUAL' | string

/** An event as the team sees it: whether it's hidden, who found it where, and whose it is */
export interface TeamEvent extends ListedEvent {
  description: string | null
  hidden: boolean
  /** Whether the team gave it its region, rather than its place or its organisation telling it */
  regionManual: boolean
  source: EventSource
  sourceUrl: string | null
  organisation: { id: string, name: string }
}

export const EVENT_FIELDS = `id title url start end hasStartTime hasEndTime locationName locationAddress promoted region
  place { name address fromOrganisation } meetup { name logo mainColor backgroundColor }`

/** An event's fields for the team, on top of everyone's */
export const TEAM_EVENT_FIELDS = `${EVENT_FIELDS} description hidden regionManual source sourceUrl organisation { id name }`

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
  /** Its region, a scraper's event's too; empty for the one its place tells, else its organisation's */
  region?: Region | ''
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

/** The day after [day] */
export function nextDay(day: string) {
  const date = utc(day)
  date.setUTCDate(date.getUTCDate() + 1)
  return dayString(date)
}

/**
 * A part of the Events page, by how soon its events are: today's (those on already too), the rest of this week's, next
 * week's, the next two months that have any (month: YYYY-MM), then everything later
 */
export interface EventBucket<E> {
  key: string
  kind: 'today' | 'thisWeek' | 'nextWeek' | 'month' | 'later'
  days: EventDay<E>[]
  count: number
}

/** [events], the soonest first, by how soon they are from [today] (a day in Ljubljana), then by the day they start */
export function byBucket<E extends Pick<ListedEvent, 'start'>>(events: E[], today: string): EventBucket<E>[] {
  const thisWeek = weekOf(today)
  const nextWeek = weekOf(dayString(new Date(utc(thisWeek).getTime() + 7 * 86_400_000)))
  const buckets = new Map<string, EventBucket<E>>()
  for (const event of [...events].sort((a, b) => a.start.localeCompare(b.start))) {
    const day = dayOf(event.start)
    const kind = day <= today ? 'today' : weekOf(day) === thisWeek ? 'thisWeek' : weekOf(day) === nextWeek ? 'nextWeek' : 'month'
    const key = kind === 'month' ? day.slice(0, 7) : kind
    const bucket = buckets.get(key) ?? { key, kind, days: [], count: 0 }
    const last = bucket.days[bucket.days.length - 1]
    if (last?.day === day) last.events.push(event)
    else bucket.days.push({ day, events: [event] })
    bucket.count++
    buckets.set(key, bucket)
  }
  // Months further off have an event or two each: one part for all of them
  const all = [...buckets.values()]
  const months = all.filter((bucket) => bucket.kind === 'month')
  if (months.length <= MONTHS_APART + 1) return all
  const later = months.slice(MONTHS_APART)
  return [
    ...all.filter((bucket) => !later.includes(bucket)),
    { key: 'later', kind: 'later', days: later.flatMap((bucket) => bucket.days), count: later.reduce((sum, bucket) => sum + bucket.count, 0) },
  ]
}

// How many months have a part of their own before Later
const MONTHS_APART = 2

/**
 * When an event is, on its row in a part of the Events page ([kind]): its day, left out today as the part says it, a
 * weekday in the two weeks, with its date further off, or its days when it's on for several; and its time, where it
 * has one, with its end's on the same day
 */
export function rowWhen(
  event: Pick<ListedEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>, kind: EventBucket<unknown>['kind'], dateLocale: DateLocale,
): { day: string, time: string } {
  const startDay = dayOf(event.start)
  const endDay = event.end ? dayOf(event.end) : startDay
  if (endDay !== startDay) return { day: `${shortDay(startDay, dateLocale)} – ${shortDay(endDay, dateLocale)}`, time: '' }
  const weekday = utc(startDay).toLocaleDateString(dateLocale, { weekday: 'short', timeZone: 'UTC' })
  const day = kind === 'today' ? '' : kind === 'thisWeek' || kind === 'nextWeek' ? weekday : `${weekday} ${shortDay(startDay, dateLocale)}`
  if (!event.hasStartTime) return { day, time: '' }
  const start = time(event.start, dateLocale)
  const ends = event.end && event.hasEndTime && event.end !== event.start
  return { day, time: ends ? `${start} – ${time(event.end!, dateLocale)}` : start }
}

/**
 * When one of Ogrodje's picks is, on its card: Today or Tomorrow, else its weekday, then its date and its time, e.g.
 * Jutri, 20. 11. · 18:00, or Sobota, 21. 11. · 09:00
 */
export function pickWhen(event: Pick<ListedEvent, 'start' | 'hasStartTime'>, today: string, t: Translate, dateLocale: DateLocale) {
  const day = dayOf(event.start)
  const name = day <= today ? t('events.today') : day === nextDay(today) ? t('events.tomorrow') : weekdayName(day, dateLocale)
  const date = `${name}, ${shortDay(day, dateLocale)}`
  return event.hasStartTime ? `${date} · ${time(event.start, dateLocale)}` : date
}

/** A bucket's heading: Today, This week, Next week, its month, e.g. December 2026, or Later */
export function bucketTitle(bucket: Pick<EventBucket<unknown>, 'key' | 'kind'>, t: Translate, dateLocale: DateLocale) {
  if (bucket.kind !== 'month') return t(`events.buckets.${bucket.kind}`)
  // (events.buckets.today, .thisWeek, .nextWeek and .later)
  return capitalized(utc(`${bucket.key}-01`).toLocaleDateString(dateLocale, { month: 'long', year: 'numeric', timeZone: 'UTC' }), dateLocale)
}

/** The first [limit] events of [days], as a folded part of the page shows them */
export function firstEvents<E>(days: EventDay<E>[], limit: number): EventDay<E>[] {
  const shown: EventDay<E>[] = []
  let left = limit
  for (const day of days) {
    if (left <= 0) break
    shown.push({ day: day.day, events: day.events.slice(0, left) })
    left -= day.events.length
  }
  return shown
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

// A place that isn't one, which no map has (as isOnline in Events.kt)
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

/** An event's own page, at ogrodje.si's address of the events: /dogodki/<ID> */
export const eventPath = (event: Pick<ListedEvent, 'id'>) => `/dogodki/${event.id}`

/** An event's organisation, as its page's panel has it */
export interface EventOrganiser {
  id: string
  name: string
  description: string | null
  logo: Image | null
  websiteUrl: string | null
  linkedinUrl: string | null
  twitterUrl: string | null
  instagramUrl: string | null
  facebookUrl: string | null
}

/** An event as its page has it: its description, and up to three of its organiser's others (moreFromOrganiser) */
export interface EventDetails extends ListedEvent {
  description: string | null
  hidden: boolean
  /** Who it's by, as its page's panel shows them: what it is, its logo and where it's online, all public */
  organisation: EventOrganiser
  moreFromOrganiser: ListedEvent[]
}

export const EVENT_PAGE = `query ($id: ID!) { event(id: $id) { ${EVENT_FIELDS} description hidden
  organisation { id name description logo { ${IMAGE_FIELDS} } websiteUrl linkedinUrl twitterUrl instagramUrl facebookUrl }
  moreFromOrganiser { ${EVENT_FIELDS} } } }`

/** Whether [event] is over at [now]: it ended, or started when it has no end, as Events.kt's upcoming */
export function isPast(event: Pick<ListedEvent, 'start' | 'end'>, now: number) {
  return new Date(event.end ?? event.start).getTime() < now
}

// e.g. Torek, 10. november 2026, or Tuesday 10 November 2026
const longDay = (at: string, dateLocale: DateLocale) => capitalized(new Date(at).toLocaleDateString(dateLocale,
  { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: TIME_ZONE }), dateLocale)

/**
 * When an event is, for its page, in Ljubljana's time: the day it starts, with its weekday, and the day it ends when
 * that's another; its start's and its end's times, where it has them
 */
export function eventSchedule(event: Pick<ListedEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>, dateLocale: DateLocale) {
  const { end } = event
  const otherDay = !!end && dayOf(end) !== dayOf(event.start)
  return {
    startDay: longDay(event.start, dateLocale),
    endDay: otherDay ? longDay(end, dateLocale) : null,
    startTime: event.hasStartTime ? time(event.start, dateLocale) : null,
    endTime: end && event.hasEndTime && end !== event.start ? time(end, dateLocale) : null,
  }
}

/** A description's paragraphs, by its blank lines; its single line breaks stay within them */
export function paragraphs(text: string | null | undefined): string[] {
  return (text ?? '').replace(/\r\n?/g, '\n').split(/\n[ \t]*\n+/).map((part) => part.trim()).filter(Boolean)
}
