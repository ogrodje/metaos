import { describe, expect, it } from 'vitest'
import { badgeColors, byWeek, colorOf, dayOf, eventTime, weekdayName, weekOf, weekRange } from '@/events'

const at = (start: string, end: string | null, hasStartTime = true, hasEndTime = true) => ({ start, end, hasStartTime, hasEndTime })

describe('eventTime', () => {
  it('says the end time alone on the same day, and its date on another', () => {
    expect(eventTime(at('2026-11-10T10:00:00Z', '2026-11-10T11:00:00Z'))).toBe('10. nov. 2026 11:00 – 12:00')
    expect(eventTime(at('2026-11-11T14:00:00Z', '2026-11-25T16:30:00Z'))).toBe('11. nov. 2026 15:00 – 25. nov. 2026 17:30')
  })

  it('leaves out an end without a time, an end the same as the start, and one there is none of', () => {
    expect(eventTime(at('2026-11-11T13:00:00Z', '2026-11-11T15:00:00Z', true, false))).toBe('11. nov. 2026 14:00')
    expect(eventTime(at('2026-11-11T13:00:00Z', '2026-11-12T15:00:00Z', true, false))).toBe('11. nov. 2026 14:00 – 12. nov. 2026')
    expect(eventTime(at('2026-11-10T17:00:00Z', '2026-11-10T17:00:00Z'))).toBe('10. nov. 2026 18:00')
    expect(eventTime(at('2026-11-17T17:00:00Z', null))).toBe('17. nov. 2026 18:00')
  })

  it('says the days of one without times, in Ljubljana', () => {
    expect(eventTime(at('2026-11-22T23:00:00Z', '2026-11-24T23:00:00Z', false, false))).toBe('23. nov. 2026 – 25. nov. 2026')
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
    expect(weekRange('2026-11-30')).toBe('30. 11. – 6. 12. 2026')
    expect(weekdayName('2026-11-10')).toBe('Torek')
    expect(weekdayName('2026-11-12')).toBe('Četrtek')
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
