import { describe, expect, it } from 'vitest'
import { ALL_REGIONS, eventText, inRegion, regionName, regionOptions, regionSlug, shownOptions, bucketTitle, byBucket, firstEvents, nextDay, pickWhen, rowWhen } from '@/events'
import { badgeColors, byWeek, colorOf, dayOf, eventPath, eventSchedule, eventTime, isPast, paragraphs, formTime, inputTime, isManual, isOnline, mapUrl, placeLine, shortDay, sourceName, weekdayName, weekOf, weekRange } from '@/events'
import { t, tSl } from './i18n'

const at = (start: string, end: string | null, hasStartTime = true, hasEndTime = true) => ({ start, end, hasStartTime, hasEndTime })

describe('eventTime', () => {
  it('says the end time alone on the same day, and its date on another', () => {
    expect(eventTime(at('2026-11-10T10:00:00Z', '2026-11-10T11:00:00Z'), 'sl-SI')).toBe('10. nov. 2026 11:00 – 12:00')
    expect(eventTime(at('2026-11-11T14:00:00Z', '2026-11-25T16:30:00Z'), 'sl-SI')).toBe('11. nov. 2026 15:00 – 25. nov. 2026 17:30')
  })

  it('leaves out an end without a time, an end the same as the start, and one there is none of', () => {
    expect(eventTime(at('2026-11-11T13:00:00Z', '2026-11-11T15:00:00Z', true, false), 'sl-SI')).toBe('11. nov. 2026 14:00')
    expect(eventTime(at('2026-11-11T13:00:00Z', '2026-11-12T15:00:00Z', true, false), 'sl-SI')).toBe('11. nov. 2026 14:00 – 12. nov. 2026')
    expect(eventTime(at('2026-11-10T17:00:00Z', '2026-11-10T17:00:00Z'), 'sl-SI')).toBe('10. nov. 2026 18:00')
    expect(eventTime(at('2026-11-17T17:00:00Z', null), 'sl-SI')).toBe('17. nov. 2026 18:00')
  })

  it('says the days of one without times, in Ljubljana', () => {
    expect(eventTime(at('2026-11-22T23:00:00Z', '2026-11-24T23:00:00Z', false, false), 'sl-SI')).toBe('23. nov. 2026 – 25. nov. 2026')
  })
})

describe('byWeek', () => {
  it('groups by the week, Monday to Sunday, and the day the event starts in Ljubljana', () => {
    const weeks = byWeek([
      { id: 'c', start: '2026-11-17T08:00:00Z' },
      { id: 'a', start: '2026-11-10T09:00:00Z' },
      { id: 'b', start: '2026-11-15T23:30:00Z' }, // Monday the 16th in Ljubljana
      { id: 'd', start: '2026-11-10T16:00:00Z' },
    ])
    expect(weeks.map((week) => week.monday)).toEqual(['2026-11-09', '2026-11-16'])
    expect(weeks[0]!.days).toEqual([{ day: '2026-11-10', events: [{ id: 'a', start: '2026-11-10T09:00:00Z' }, { id: 'd', start: '2026-11-10T16:00:00Z' }] }])
    expect(weeks[1]!.days.map((day) => day.day)).toEqual(['2026-11-16', '2026-11-17'])
    expect(weeks.map((week) => week.count)).toEqual([2, 2])
  })

  it('names the weeks and days in Slovenian', () => {
    expect(dayOf('2026-11-15T23:30:00Z')).toBe('2026-11-16')
    expect(weekOf('2026-11-15')).toBe('2026-11-09')
    expect(weekRange('2026-11-30', 'sl-SI')).toBe('30. 11. – 6. 12. 2026')
    expect(weekRange('2026-11-09', 'sl-SI')).toBe('9. 11. – 15. 11. 2026')
    expect(tSl('events.week', { range: weekRange('2026-11-09', 'sl-SI') })).toBe('Teden / 9. 11. – 15. 11. 2026')
    expect(weekdayName('2026-11-10', 'sl-SI')).toBe('Torek')
    expect(weekdayName('2026-11-12', 'sl-SI')).toBe('Četrtek')
    expect(shortDay('2026-11-10', 'sl-SI')).toBe('10. 11.')
  })

  it('names them in English too', () => {
    // Intl puts thin spaces around the dash
    const spaced = (text: string) => text.replace(/\s/g, ' ')
    expect(spaced(weekRange('2026-11-09', 'en-GB'))).toBe('9 – 15 Nov 2026')
    expect(spaced(weekRange('2026-11-30', 'en-GB'))).toBe('30 Nov – 6 Dec 2026')
    expect(spaced(t('events.week', { range: weekRange('2026-11-09', 'en-GB') }))).toBe('Week of 9 – 15 Nov 2026')
    expect(weekdayName('2026-11-10', 'en-GB')).toBe('Tuesday')
    expect(shortDay('2026-11-10', 'en-GB')).toBe('10 Nov')
    expect(eventTime(at('2026-11-10T10:00:00Z', '2026-11-10T11:00:00Z'), 'en-GB')).toBe('10 Nov 2026 11:00 – 12:00')
  })
})

describe('badgeColors', () => {
  it("are the meetup's own, or white on a colour from its name", () => {
    expect(badgeColors({ name: 'Kovačnica', mainColor: '#ffffff', backgroundColor: '#000000' })).toEqual({ color: '#ffffff', background: '#000000' })
    expect(badgeColors({ name: 'Start:up Slovenija', mainColor: null, backgroundColor: null }))
      .toEqual({ color: '#FFFFFF', background: colorOf('Start:up Slovenija') })
    expect(colorOf('Start:up Slovenija')).toBe(colorOf('Start:up Slovenija'))
    expect(colorOf('Start:up Slovenija')).not.toBe(colorOf('Kovačnica'))
  })
})

describe("an event's form", () => {
  it('shows a time as the day and the time in Ljubljana, and a day alone without one', () => {
    expect(formTime('2026-11-16T17:00:00Z', true)).toEqual({ day: '2026-11-16', time: '18:00' })
    expect(formTime('2026-07-16T22:30:00Z', true)).toEqual({ day: '2026-07-17', time: '00:30' })
    expect(formTime('2026-11-24T23:00:00Z', false)).toEqual({ day: '2026-11-25', time: '' })
    expect(formTime(null, false)).toEqual({ day: '', time: '' })
  })

  it('gives MetaOS a time in Ljubljana, a day, or nothing', () => {
    expect(inputTime({ day: '2026-11-16', time: '18:00' })).toBe('2026-11-16T18:00')
    expect(inputTime({ day: '2026-11-16', time: '' })).toBe('2026-11-16')
    expect(inputTime({ day: '', time: '18:00' })).toBe('')
  })

  it('goes there and back as it was', () => {
    expect(inputTime(formTime('2026-03-29T08:15:00Z', true))).toBe('2026-03-29T10:15')
  })

  it('tells the team their own events from those a scraper found', () => {
    expect(isManual({ source: 'MANUAL' })).toBe(true)
    expect(isManual({ source: 'MEETUP' })).toBe(false)
  })
})

describe('placeLine', () => {
  it('says the place and its address, or either, and the place once where the address has it', () => {
    expect(placeLine({ name: 'Kovačnica', address: 'Trubarjeva 5, Ljubljana' })).toBe('Kovačnica, Trubarjeva 5, Ljubljana')
    expect(placeLine({ name: 'Kovačnica', address: null })).toBe('Kovačnica')
    expect(placeLine({ name: null, address: 'Trubarjeva 5' })).toBe('Trubarjeva 5')
    expect(placeLine({ name: 'FRI', address: 'FRI, Večna pot 113' })).toBe('FRI, Večna pot 113')
    expect(placeLine({ name: ' ', address: null })).toBeNull()
    expect(placeLine(null)).toBeNull()
  })
})

describe('mapUrl', () => {
  it('links a place to the map, but not one online', () => {
    expect(mapUrl({ name: 'Kovačnica', address: 'Trubarjeva 5' }))
      .toBe('https://www.google.com/maps/search/?api=1&query=Kova%C4%8Dnica%2C%20Trubarjeva%205')
    expect(mapUrl({ name: 'Spletni dogodek', address: null })).toBeNull()
    expect(isOnline({ name: 'Online (Zoom)', address: null })).toBe(true)
    expect(isOnline({ name: 'Kovačnica', address: null })).toBe(false)
    expect(mapUrl({ name: null, address: null })).toBeNull()
  })
})

describe('sourceName', () => {
  it('names the scrapers as their sites do, and any other by its code', () => {
    expect(sourceName('MEETUP')).toBe('Meetup')
    expect(sourceName('ICAL')).toBe('iCal')
    expect(sourceName('TEHNOLOSKI_PARK_LJUBLJANA')).toBe('Tehnološki park Ljubljana')
    expect(sourceName('NEW_SCRAPER')).toBe('New Scraper')
  })
})

describe("an event's page", () => {
  it('is at /dogodki/<ID>', () => {
    expect(eventPath({ id: 'abc' })).toBe('/dogodki/abc')
  })

  it('says the day with its weekday, and the times where there are any, in Ljubljana', () => {
    expect(eventSchedule(at('2026-11-10T17:00:00Z', '2026-11-10T19:30:00Z'), 'sl-SI'))
      .toEqual({ startDay: 'Torek, 10. november 2026', endDay: null, startTime: '18:00', endTime: '20:30' })
    expect(eventSchedule(at('2026-11-10T17:00:00Z', '2026-11-10T19:30:00Z'), 'en-GB'))
      .toEqual({ startDay: 'Tuesday, 10 November 2026', endDay: null, startTime: '18:00', endTime: '20:30' })
    // A day's event, which starts at midnight in Ljubljana, the day before in UTC
    expect(eventSchedule(at('2026-11-22T23:00:00Z', '2026-11-24T23:00:00Z', false, false), 'sl-SI'))
      .toEqual({ startDay: 'Ponedeljek, 23. november 2026', endDay: 'Sreda, 25. november 2026', startTime: null, endTime: null })
    expect(eventSchedule(at('2026-11-10T17:00:00Z', null), 'sl-SI').endTime).toBeNull()
    expect(eventSchedule(at('2026-11-10T17:00:00Z', '2026-11-10T17:00:00Z'), 'sl-SI').endTime).toBeNull()
  })

  it('tells a past event, over once it ended, or started without an end', () => {
    const now = Date.parse('2026-11-18T12:00:00Z')
    expect(isPast({ start: '2026-11-17T17:00:00Z', end: '2026-11-17T19:00:00Z' }, now)).toBe(true)
    expect(isPast({ start: '2026-11-17T17:00:00Z', end: '2026-11-19T19:00:00Z' }, now)).toBe(false)
    expect(isPast({ start: '2026-11-18T11:00:00Z', end: null }, now)).toBe(true)
    expect(isPast({ start: '2026-11-20T11:00:00Z', end: null }, now)).toBe(false)
  })

  it('keeps the paragraphs of its description', () => {
    expect(paragraphs('First line\nsecond line\r\n\r\n  Next  \n\n\n\nLast')).toEqual(['First line\nsecond line', 'Next', 'Last'])
    expect(paragraphs('   ')).toEqual([])
    expect(paragraphs(null)).toEqual([])
  })
})

describe('the regions', () => {
  const events = [
    { region: 'GORENJSKA' as const }, { region: 'OSREDNJESLOVENSKA' as const }, { region: 'GORENJSKA' as const },
    { region: 'ONLINE' as const }, { region: 'PRIMORSKO_NOTRANJSKA' as const }, { region: null }, {},
  ]

  it('are named in Slovenian in both languages, but online and abroad', () => {
    expect(regionName('JUGOVZHODNA', t)).toBe('Jugovzhodna Slovenija')
    expect(regionName('OBALNO_KRASKA', tSl)).toBe('Obalno-kraška')
    expect(regionName('ONLINE', t)).toBe('Online')
    expect(regionName('ONLINE', tSl)).toBe('Na spletu')
    expect(regionName('ABROAD', tSl)).toBe('Tujina')
  })

  it('are offered where there are events, in their own order, with how many', () => {
    expect(regionOptions(events, t)).toEqual([
      { value: ALL_REGIONS, title: 'All regions' },
      { value: 'osrednjeslovenska', title: 'Osrednjeslovenska', count: 1 },
      { value: 'gorenjska', title: 'Gorenjska', count: 2 },
      { value: 'primorsko-notranjska', title: 'Primorsko-notranjska', count: 1 },
      { value: 'online', title: 'Online', count: 1 },
    ])
  })

  it('keep the one the address says, though it has no events', () => {
    expect(regionOptions(events, t, 'zasavska')).toContainEqual({ value: 'zasavska', title: 'Zasavska', count: 0 })
    expect(regionOptions([], t, 'kranjska')).toEqual([{ value: ALL_REGIONS, title: 'All regions' }])
  })

  it('filter the events, and one with no region is only among all', () => {
    expect(regionSlug('PRIMORSKO_NOTRANJSKA')).toBe('primorsko-notranjska')
    expect(events.filter((event) => inRegion(event, 'gorenjska'))).toHaveLength(2)
    expect(events.filter((event) => inRegion(event, 'primorsko-notranjska'))).toHaveLength(1)
    expect(events.filter((event) => inRegion(event, 'zasavska'))).toHaveLength(0)
    expect(events.filter((event) => inRegion(event, ALL_REGIONS))).toHaveLength(events.length)
  })

  it('find an event by its title, who it is by and where it is, its organisation\'s venue too', () => {
    const event = { title: 'Kotlin večer', meetup: { name: 'Kotlin Slovenija', logo: null, mainColor: null, backgroundColor: null }, locationName: 'FRI', locationAddress: null }
    expect(eventText({ ...event, place: { name: 'Kovačnica', address: 'Poštna ulica 4, 4000 Kranj', fromOrganisation: true } }))
      .toBe('Kotlin večer Kotlin Slovenija Kovačnica Poštna ulica 4, 4000 Kranj')
    expect(eventText({ ...event, meetup: null })).toBe('Kotlin večer FRI')
  })
})

describe('the picks', () => {
  it('are a chip beside all of them, each with how many', () => {
    expect(shownOptions([{ promoted: true }, { promoted: false }, { promoted: false }], tSl)).toEqual([
      { value: 'all', title: 'Vsi', count: 3 },
      { value: 'promoted', title: 'Izpostavljeni', count: 1 },
    ])
  })
})

describe('byBucket', () => {
  // Thursday 19 Nov 2026 in Ljubljana
  const today = '2026-11-19'
  const events = [
    { id: 'on', start: '2026-11-18T13:00:00Z' }, { id: 'now', start: '2026-11-19T17:00:00Z' },
    { id: 'sat', start: '2026-11-21T08:00:00Z' }, { id: 'sun-late', start: '2026-11-22T23:30:00Z' },
    { id: 'next', start: '2026-11-25T09:00:00Z' }, { id: 'nov', start: '2026-11-30T09:00:00Z' },
    { id: 'dec-a', start: '2026-12-01T09:00:00Z' }, { id: 'dec-b', start: '2026-12-01T10:00:00Z' }, { id: 'dec-c', start: '2026-12-09T10:00:00Z' },
    { id: 'jan', start: '2027-01-18T09:00:00Z' },
  ]

  it('groups by how soon: today with what is on already, this week, next week, then months', () => {
    const buckets = byBucket(events, today)
    expect(buckets.map((b) => [b.key, b.count])).toEqual([
      ['today', 2], ['thisWeek', 1], ['nextWeek', 2], ['2026-11', 1], ['2026-12', 3], ['2027-01', 1],
    ])
    // Months past the next two are one part
    const further = [...events, { id: 'feb', start: '2027-02-15T09:00:00Z' }, { id: 'may', start: '2027-05-17T09:00:00Z' }]
    expect(byBucket(further, today).map((b) => [b.key, b.count])).toEqual([
      ['today', 2], ['thisWeek', 1], ['nextWeek', 2], ['2026-11', 1], ['2026-12', 3], ['later', 3],
    ])
    // 23:30 UTC on Sunday is Monday in Ljubljana, so next week
    expect(buckets[2]!.days.map((d) => d.day)).toEqual(['2026-11-23', '2026-11-25'])
    expect(buckets[4]!.days.map((d) => d.events.map((e) => e.id))).toEqual([['dec-a', 'dec-b'], ['dec-c']])
  })

  it('names them in the page\'s language', () => {
    const [todays, , , , december] = byBucket(events, today)
    expect(bucketTitle(todays!, tSl, 'sl-SI')).toBe('Danes')
    expect(bucketTitle(december!, tSl, 'sl-SI')).toBe('December 2026')
    expect(bucketTitle(december!, t, 'en-GB')).toBe('December 2026')
  })

  it('folds a part to its first events, and knows tomorrow', () => {
    const december = byBucket(events, today)[4]!
    expect(firstEvents(december.days, 1).map((d) => d.events.map((e) => e.id))).toEqual([['dec-a']])
    expect(firstEvents(december.days, 2).map((d) => d.events.length)).toEqual([2])
    expect(firstEvents(december.days, 5)).toEqual(december.days)
    expect(nextDay('2026-11-30')).toBe('2026-12-01')
  })
})

describe('when an event is, on its row and on a pick\'s card', () => {
  const talk = { start: '2026-11-20T17:00:00Z', end: '2026-11-20T19:00:00Z', hasStartTime: true, hasEndTime: true }

  it('is as short as its part of the page allows', () => {
    expect(rowWhen(talk, 'today', 'sl-SI')).toEqual({ day: '', time: '18:00 – 20:00' })
    expect(rowWhen(talk, 'thisWeek', 'sl-SI')).toEqual({ day: 'pet.', time: '18:00 – 20:00' })
    expect(rowWhen(talk, 'month', 'sl-SI')).toEqual({ day: 'pet. 20. 11.', time: '18:00 – 20:00' })
    expect(rowWhen({ ...talk, end: null }, 'later', 'en-GB')).toEqual({ day: 'Fri 20 Nov', time: '18:00' })
  })

  it('says the days of one on for several, and no time for one without', () => {
    expect(rowWhen({ ...talk, end: '2026-11-25T15:30:00Z' }, 'today', 'sl-SI')).toEqual({ day: '20. 11. – 25. 11.', time: '' })
    expect(rowWhen({ ...talk, end: null, hasStartTime: false }, 'nextWeek', 'sl-SI')).toEqual({ day: 'pet.', time: '' })
  })

  it('says Today and Tomorrow on a card, else the weekday', () => {
    expect(pickWhen(talk, '2026-11-20', tSl, 'sl-SI')).toBe('Danes, 20. 11. · 18:00')
    expect(pickWhen(talk, '2026-11-19', tSl, 'sl-SI')).toBe('Jutri, 20. 11. · 18:00')
    expect(pickWhen({ ...talk, hasStartTime: false }, '2026-11-10', t, 'en-GB')).toBe('Friday, 20 Nov')
  })
})
