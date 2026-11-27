import { describe, expect, it } from 'vitest'
import {
  calendar, type CalendarEvent, calendarName, calendarPath, escapeText, eventDescription, eventFilePath, eventLinks, eventSpan,
  eventTimes, fold, googleCalendarUrl, googleEventUrl, outlookCalendarUrl, outlookEventUrl, regionOf, subscribeLinks, webcalUrl,
  yahooEventUrl,
} from '@/calendar'
import { inRegion, REGIONS } from '@/events'

const at = (start: string, end: string | null, hasStartTime = true, hasEndTime = true) => ({ start, end, hasStartTime, hasEndTime })

const octets = (text: string) => new TextEncoder().encode(text).length

// A folded property as apps read it: its lines put together again
const unfold = (text: string) => text.replace(/\r\n /g, '')

const event = (fields: Partial<CalendarEvent> = {}): CalendarEvent => ({
  id: '4f6c2a1e-0000-4000-8000-000000000001',
  title: 'Srečanje',
  url: 'https://example.org/srecanje',
  start: '2026-11-10T17:00:00Z',
  end: '2026-11-10T19:00:00Z',
  hasStartTime: true,
  hasEndTime: true,
  ...fields,
})

const OPTIONS = { name: 'Ogrodje – dogodki', siteUrl: 'https://ogrodje.si', now: new Date('2026-11-01T08:30:00Z') }

describe('escapeText', () => {
  it('escapes backslashes, semicolons, commas and line breaks', () => {
    expect(escapeText('C:\\pot; a, b')).toBe('C:\\\\pot\\; a\\, b')
    expect(escapeText('prva\r\ndruga\ntretja\rčetrta')).toBe('prva\\ndruga\\ntretja\\nčetrta')
  })

  it('drops other control characters', () => {
    expect(escapeText('a\u0000b\tc\u007Fd')).toBe('abcd')
  })
})

describe('fold', () => {
  it('leaves a line of 75 octets as it is', () => {
    const line = 'x'.repeat(75)
    expect(fold(line)).toBe(line)
  })

  it('folds by octets, with a space before each next line, never inside a character', () => {
    const line = `SUMMARY:${'čšž'.repeat(40)}`
    const folded = fold(line)
    const lines = folded.split('\r\n')
    expect(lines.length).toBeGreaterThan(2)
    for (const each of lines) expect(octets(each)).toBeLessThanOrEqual(75)
    for (const each of lines.slice(1)) expect(each.startsWith(' ')).toBe(true)
    // Cut inside a character, it wouldn't be the same text again
    expect(unfold(folded)).toBe(line)
    // By characters, the first line would have been 75 of them, 142 octets
    expect(lines[0]!.length).toBeLessThan(75)
  })

  it('keeps an emoji whole', () => {
    const line = `SUMMARY:${'🗓'.repeat(30)}`
    const folded = fold(line)
    for (const each of folded.split('\r\n')) expect(octets(each)).toBeLessThanOrEqual(75)
    expect(unfold(folded)).toBe(line)
  })
})

describe('eventTimes', () => {
  it('says instants in UTC for an event with times', () => {
    expect(eventTimes(at('2026-11-10T17:00:00Z', '2026-11-10T19:00:00Z'))).toEqual(['DTSTART:20261110T170000Z', 'DTEND:20261110T190000Z'])
    expect(eventTimes(at('2026-11-10T18:00:00+01:00', '2026-11-10T20:00:00+01:00'))).toEqual(['DTSTART:20261110T170000Z', 'DTEND:20261110T190000Z'])
  })

  it('makes up no end: without one, with one the same as the start, or one without a time on the same day', () => {
    expect(eventTimes(at('2026-11-17T17:00:00Z', null, true, false))).toEqual(['DTSTART:20261117T170000Z'])
    expect(eventTimes(at('2026-11-17T17:00:00Z', '2026-11-17T17:00:00Z'))).toEqual(['DTSTART:20261117T170000Z'])
    expect(eventTimes(at('2026-11-17T15:00:00Z', '2026-11-17T17:00:00Z', true, false))).toEqual(['DTSTART:20261117T150000Z'])
  })

  it('makes one without a time its day in Ljubljana, whole', () => {
    // Midnight in Ljubljana is the day before's 23:00 in UTC
    expect(eventTimes(at('2027-01-17T23:00:00Z', null, false, false))).toEqual(['DTSTART;VALUE=DATE:20270118', 'DTEND;VALUE=DATE:20270119'])
    // As the iCal scraper has a whole day: 00:01 to 23:59
    expect(eventTimes(at('2026-11-30T23:01:00Z', '2026-12-01T22:59:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20261201', 'DTEND;VALUE=DATE:20261202'])
    expect(eventTimes(at('2026-11-30T23:00:00Z', '2026-11-30T23:00:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20261201', 'DTEND;VALUE=DATE:20261202'])
  })

  it('ends one of several days the day after its last, however its end is written', () => {
    // The team's own: 23:59 of its last day
    expect(eventTimes(at('2026-11-22T23:00:00Z', '2026-11-25T22:59:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20261123', 'DTEND;VALUE=DATE:20261126'])
    // A scraper's: midnight as its last day starts (Katapult's 26.–28. 11.)
    expect(eventTimes(at('2026-11-25T23:00:00Z', '2026-11-27T23:00:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20261126', 'DTEND;VALUE=DATE:20261129'])
  })

  it('makes one that starts at a time and ends days later without one its days', () => {
    expect(eventTimes(at('2026-11-11T13:00:00Z', '2026-11-12T13:00:00Z', true, false))).toEqual(['DTSTART;VALUE=DATE:20261111', 'DTEND;VALUE=DATE:20261113'])
  })

  it('goes by Ljubljana\'s days across the change to summer time and back', () => {
    // 29 March 2026: midnight is 23:00 UTC before it and 22:00 UTC after
    expect(eventTimes(at('2026-03-28T23:00:00Z', '2026-03-29T21:59:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20260329', 'DTEND;VALUE=DATE:20260330'])
    expect(eventTimes(at('2026-03-28T23:00:00Z', '2026-03-29T22:00:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20260329', 'DTEND;VALUE=DATE:20260331'])
    // 25 October 2026: 22:00 UTC before it, 23:00 UTC after
    expect(eventTimes(at('2026-10-24T22:00:00Z', '2026-10-25T22:59:00Z', false, false))).toEqual(['DTSTART;VALUE=DATE:20261025', 'DTEND;VALUE=DATE:20261026'])
    // With times, an instant is an instant: 18:00 in Ljubljana is 16:00 UTC in summer and 17:00 in winter
    expect(eventTimes(at('2026-07-01T18:00:00+02:00', null))).toEqual(['DTSTART:20260701T160000Z'])
    expect(eventTimes(at('2026-12-01T18:00:00+01:00', null))).toEqual(['DTSTART:20261201T170000Z'])
  })
})

describe('eventDescription', () => {
  it('says who it is by, what they wrote and where it is announced', () => {
    expect(eventDescription({ meetup: { name: 'Python Slovenija' }, description: ' Večer o Pythonu. ', url: 'https://example.org/a' }))
      .toBe('Organizator: Python Slovenija\n\nVečer o Pythonu.\n\nVeč o dogodku: https://example.org/a')
  })

  it('leaves out what there is none of', () => {
    expect(eventDescription({ meetup: null, description: null, url: null })).toBe('')
    expect(eventDescription({ meetup: { name: 'FRI' }, description: '', url: null })).toBe('Organizator: FRI')
  })
})

describe('calendar', () => {
  it('is a calendar with a name, read again every hour, its lines ended with CRLF', () => {
    const text = calendar([], OPTIONS)
    expect(text).toBe([
      'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Ogrodje//MetaOS//SL', 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH',
      'X-WR-CALNAME:Ogrodje – dogodki', 'X-WR-TIMEZONE:Europe/Ljubljana', 'REFRESH-INTERVAL;VALUE=DURATION:PT1H',
      'X-PUBLISHED-TTL:PT1H', 'END:VCALENDAR', '',
    ].join('\r\n'))
  })

  it('has an event with its ID, times, place, description and page', () => {
    const text = unfold(calendar([event({
      title: 'Žur; pri nas, zunaj',
      description: 'Prva vrstica\nDruga',
      place: { name: 'Računalniški muzej', address: 'Celovška cesta 111, 1000 Ljubljana' },
      meetup: { name: 'Ogrodje' },
    })], OPTIONS))
    expect(text.split('\r\n').slice(9, -2)).toEqual([
      'BEGIN:VEVENT',
      'UID:4f6c2a1e-0000-4000-8000-000000000001@ogrodje.si',
      'DTSTAMP:20261101T083000Z',
      'DTSTART:20261110T170000Z',
      'DTEND:20261110T190000Z',
      'SUMMARY:Žur\\; pri nas\\, zunaj',
      'LOCATION:Računalniški muzej\\, Celovška cesta 111\\, 1000 Ljubljana',
      'DESCRIPTION:Organizator: Ogrodje\\n\\nPrva vrstica\\nDruga\\n\\nVeč o dogodku: https://example.org/srecanje',
      'URL:https://ogrodje.si/dogodki/4f6c2a1e-0000-4000-8000-000000000001',
      'END:VEVENT',
    ])
  })

  it('leaves out a place and a description there is none of', () => {
    const text = calendar([event({ url: null })], OPTIONS)
    expect(text).not.toContain('LOCATION')
    expect(text).not.toContain('DESCRIPTION')
  })

  it('lists the soonest first, and folds every line', () => {
    const text = calendar([
      event({ id: 'b', start: '2026-12-01T17:00:00Z', end: null, description: 'šč'.repeat(300) }),
      event({ id: 'a' }),
    ], OPTIONS)
    expect(text.indexOf('UID:a@')).toBeLessThan(text.indexOf('UID:b@'))
    for (const line of text.split('\r\n')) expect(octets(line)).toBeLessThanOrEqual(75)
    expect(text).not.toMatch(/[^\r]\n/)
  })
})

describe('the address and its filters', () => {
  it('has the page\'s filters, but not those left as they are', () => {
    expect(calendarPath()).toBe('/dogodki.ics')
    expect(calendarPath('gorenjska')).toBe('/dogodki.ics?region=gorenjska')
    expect(calendarPath('all', 'promoted')).toBe('/dogodki.ics?show=promoted')
    expect(calendarPath('primorsko-notranjska', 'promoted')).toBe('/dogodki.ics?region=primorsko-notranjska&show=promoted')
  })

  it('is webcal\'s for calendar apps', () => {
    expect(webcalUrl('https://ogrodje.si/dogodki.ics?region=gorenjska')).toBe('webcal://ogrodje.si/dogodki.ics?region=gorenjska')
    expect(webcalUrl('http://localhost:5173/dogodki.ics')).toBe('webcal://localhost:5173/dogodki.ics')
  })

  it('is asked for by Google Calendar at its own address, with the calendar\'s whole, filters too', () => {
    expect(googleCalendarUrl('https://ogrodje.si/dogodki.ics'))
      .toBe('https://calendar.google.com/calendar/render?cid=webcal%3A%2F%2Fogrodje.si%2Fdogodki.ics')
    const filtered = googleCalendarUrl('https://ogrodje.si/dogodki.ics?region=gorenjska&show=promoted')
    // Not Google's own parameters
    expect([...new URL(filtered).searchParams.keys()]).toEqual(['cid'])
    expect(new URL(filtered).searchParams.get('cid')).toBe('webcal://ogrodje.si/dogodki.ics?region=gorenjska&show=promoted')
  })

  it('is asked for by Outlook on the web, Microsoft 365\'s or Outlook.com\'s, with its name', () => {
    const url = 'https://ogrodje.si/dogodki.ics?region=gorenjska&show=promoted'
    const name = 'Ogrodje – dogodki (Obalno-kraška, izpostavljeni)'
    const office = new URL(outlookCalendarUrl(url, name, 'office'))
    expect(office.origin + office.pathname).toBe('https://outlook.office.com/calendar/0/addfromweb')
    expect([...office.searchParams.keys()]).toEqual(['url', 'name'])
    expect(office.searchParams.get('url')).toBe(url)
    expect(office.searchParams.get('name')).toBe(name)
    const live = new URL(outlookCalendarUrl(url, name, 'live'))
    expect(live.origin + live.pathname).toBe('https://outlook.live.com/calendar/0/addfromweb')
    expect(live.search).toBe(office.search)
  })

  it('finds the region of a slug, which the events are filtered by', () => {
    expect(regionOf('primorsko-notranjska', REGIONS)).toBe('PRIMORSKO_NOTRANJSKA')
    expect(regionOf('nowhere', REGIONS)).toBeNull()
    const events = [{ region: 'GORENJSKA' as const }, { region: 'ONLINE' as const }, { region: null }]
    expect(events.filter((each) => inRegion(each, 'gorenjska'))).toEqual([{ region: 'GORENJSKA' }])
    expect(events.filter((each) => inRegion(each, 'all'))).toHaveLength(3)
  })

  it('names the calendar after what it is filtered to', () => {
    const name = (region: string) => region.toLowerCase()
    expect(calendarName(null, 'all', name)).toBe('Ogrodje – dogodki')
    expect(calendarName('GORENJSKA', 'all', name)).toBe('Ogrodje – dogodki (gorenjska)')
    expect(calendarName('ONLINE', 'promoted', name)).toBe('Ogrodje – dogodki (online, izpostavljeni)')
  })
})

describe('an event alone', () => {
  const params = (url: string) => Object.fromEntries(new URL(url).searchParams)
  const timed = event({
    title: 'Žur & pol; pri nas',
    description: 'Prva vrstica\nDruga',
    place: { name: 'Računalniški muzej', address: 'Celovška cesta 111, 1000 Ljubljana' },
    meetup: { name: 'Ogrodje' },
  })
  const days = event({ start: '2026-11-25T23:00:00Z', end: '2026-11-27T23:00:00Z', hasStartTime: false, hasEndTime: false, url: null })
  const noEnd = event({ end: null, hasEndTime: false })

  it('is when the calendar says it is', () => {
    expect(eventSpan(timed)).toEqual({ allDay: false, start: new Date('2026-11-10T17:00:00Z'), end: new Date('2026-11-10T19:00:00Z') })
    expect(eventSpan(noEnd)).toEqual({ allDay: false, start: new Date('2026-11-10T17:00:00Z'), end: null })
    expect(eventSpan(days)).toEqual({ allDay: true, firstDay: '2026-11-26', dayAfter: '2026-11-29' })
  })

  it('is made by Google Calendar from its address', () => {
    const url = googleEventUrl(timed)
    expect(url.startsWith('https://calendar.google.com/calendar/render?action=TEMPLATE&')).toBe(true)
    expect(params(url)).toEqual({
      action: 'TEMPLATE',
      text: 'Žur & pol; pri nas',
      dates: '20261110T170000Z/20261110T190000Z',
      details: 'Organizator: Ogrodje\n\nPrva vrstica\nDruga\n\nVeč o dogodku: https://example.org/srecanje',
      location: 'Računalniški muzej, Celovška cesta 111, 1000 Ljubljana',
    })
    // Whole days, to the day after the last; nothing for what there's none of
    expect(params(googleEventUrl(days))).toEqual({ action: 'TEMPLATE', text: 'Srečanje', dates: '20261126/20261129' })
    // Google wants an end: as it starts, not one made up
    expect(params(googleEventUrl(noEnd)).dates).toBe('20261110T170000Z/20261110T170000Z')
  })

  it('is made by Outlook on the web, Microsoft 365\'s or Outlook.com\'s', () => {
    const office = new URL(outlookEventUrl(timed, 'office'))
    expect(office.origin + office.pathname).toBe('https://outlook.office.com/calendar/0/deeplink/compose')
    expect(params(office.href)).toEqual({
      path: '/calendar/action/compose',
      rru: 'addevent',
      subject: 'Žur & pol; pri nas',
      startdt: '2026-11-10T17:00:00Z',
      enddt: '2026-11-10T19:00:00Z',
      body: 'Organizator: Ogrodje\n\nPrva vrstica\nDruga\n\nVeč o dogodku: https://example.org/srecanje',
      location: 'Računalniški muzej, Celovška cesta 111, 1000 Ljubljana',
    })
    const live = new URL(outlookEventUrl(timed, 'live'))
    expect(live.origin + live.pathname).toBe('https://outlook.live.com/calendar/0/deeplink/compose')
    expect(live.search).toBe(office.search)
    expect(params(outlookEventUrl(days, 'office'))).toMatchObject({ startdt: '2026-11-26', enddt: '2026-11-29', allday: 'true' })
    expect(params(outlookEventUrl(noEnd, 'live'))).toMatchObject({ startdt: '2026-11-10T17:00:00Z', enddt: '2026-11-10T17:00:00Z' })
  })

  it('is made by Yahoo Calendar', () => {
    expect(params(yahooEventUrl(timed))).toMatchObject({
      v: '60', title: 'Žur & pol; pri nas', st: '20261110T170000Z', et: '20261110T190000Z',
      in_loc: 'Računalniški muzej, Celovška cesta 111, 1000 Ljubljana',
    })
    expect(params(yahooEventUrl(timed)).dur).toBeUndefined()
    expect(params(yahooEventUrl(days))).toEqual({ v: '60', title: 'Srečanje', st: '20261126', et: '20261129', dur: 'allday' })
  })

  it('carries only the start of a long description, as an address is only so long', () => {
    const long = event({ description: 'šč '.repeat(2000), url: null })
    const { details } = params(googleEventUrl(long))
    expect(details!.length).toBeLessThanOrEqual(1000)
    expect(details!.length).toBeGreaterThan(990)
    expect(details!.endsWith('…')).toBe(true)
    expect(params(outlookEventUrl(long, 'office')).body).toBe(details)
    expect(params(yahooEventUrl(long)).desc).toBe(details)
  })

  it('is a file of its own, by its ID', () => {
    expect(eventFilePath(timed)).toBe('/dogodki.ics?event=4f6c2a1e-0000-4000-8000-000000000001')
  })
})

describe('the ways into a calendar', () => {
  it('subscribes Google\'s, Outlook\'s two and the device\'s app', () => {
    const links = subscribeLinks('https://ogrodje.si/dogodki.ics', 'Ogrodje – dogodki')
    expect(links.map((each) => [each.kind, each.external])).toEqual([['google', true], ['outlook365', true], ['outlookCom', true], ['app', false]])
    expect(links[0]!.href).toBe(googleCalendarUrl('https://ogrodje.si/dogodki.ics'))
    expect(links[3]!.href).toBe('webcal://ogrodje.si/dogodki.ics')
  })

  it('adds an event alone to Google\'s, Outlook\'s two, Yahoo\'s, or as its file at the site', () => {
    const links = eventLinks(event(), 'https://ogrodje.si')
    expect(links.map((each) => [each.kind, each.external]))
      .toEqual([['google', true], ['outlook365', true], ['outlookCom', true], ['yahoo', true], ['file', false]])
    expect(links[4]!.href).toBe('https://ogrodje.si/dogodki.ics?event=4f6c2a1e-0000-4000-8000-000000000001')
  })
})
