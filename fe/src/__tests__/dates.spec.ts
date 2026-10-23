import { describe, expect, it } from 'vitest'
import { daysFrom, relativeDate } from '@/episodes'

const today = new Date(2026, 9, 23, 22, 30)

describe('daysFrom', () => {
  it('counts calendar days, whatever the time of day', () => {
    expect(daysFrom(today, '2026-10-23')).toBe(0)
    expect(daysFrom(today, '2026-10-24')).toBe(1)
    expect(daysFrom(today, '2026-10-22T08:00:00Z')).toBe(-1)
  })
})

describe('relativeDate', () => {
  it('says days, then weeks, then months', () => {
    expect(relativeDate('2026-10-24', today)).toBe('tomorrow')
    expect(relativeDate('2026-10-22', today)).toBe('yesterday')
    expect(relativeDate('2026-10-28', today)).toBe('in 5 days')
    expect(relativeDate('2026-11-13', today)).toBe('in 3 weeks')
    expect(relativeDate('2026-07-09', today)).toBe('3 months ago')
  })
})
