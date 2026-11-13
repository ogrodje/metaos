import type { DateLocale } from '@/i18n'
import { TIME_ZONE } from '@/i18n'

// Slovenia's tech, developer and startup events, from Goo through MetaOS's API (Goo.kt), as ogrodje.si's Dogodki page
// listed them: by week, Monday to Sunday, then by day, in Ljubljana's time, whatever the browser's. Their dates are in
// the page's language: in Slovenian as ogrodje.si wrote them (9. 11. – 15. 11. 2026), or in English (9 – 15 Nov 2026).

/** Who an event is by, with its logo and its colours, the text's (mainColor) and its badge's */
export interface Meetup {
  name: string
  logo: string | null
  mainColor: string | null
  backgroundColor: string | null
}

/** An event: when it starts and ends (instants), with a time of day only where hasStartTime and hasEndTime say */
export interface GooEvent {
  id: string
  title: string
  url: string | null
  start: string
  end: string | null
  hasStartTime: boolean
  hasEndTime: boolean
  locationName: string | null
  locationAddress: string | null
  promoted: boolean
  meetup: Meetup | null
}

export const EVENT_FIELDS = `id title url start end hasStartTime hasEndTime locationName locationAddress promoted
  meetup { name logo mainColor backgroundColor }`

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
export function eventTime(event: Pick<GooEvent, 'start' | 'end' | 'hasStartTime' | 'hasEndTime'>, dateLocale: DateLocale) {
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
export function byWeek<E extends Pick<GooEvent, 'start'>>(events: E[]): EventWeek<E>[] {
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
