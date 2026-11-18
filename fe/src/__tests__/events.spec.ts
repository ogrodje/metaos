import { describe, expect, it } from 'vitest'
import { badgeColors, byWeek, colorOf, dayOf, eventTime, formTime, inputTime, isManual, isOnline, mapUrl, placeLine, shortDay, sourceName, weekdayName, weekOf, weekRange } from '@/events'
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
