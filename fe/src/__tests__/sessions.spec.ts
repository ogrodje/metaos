import { describe, expect, it } from 'vitest'
import { sessionName, sessionPlace, sessionTitle } from '@/episodes'

describe('a session', () => {
  const session = { date: '2026-10-22', name: 'AWS Community Day Adria', location: 'Kino Šiška' }

  it('goes by its name, else its location, else its date', () => {
    expect(sessionName(session)).toBe('AWS Community Day Adria')
    expect(sessionTitle(session)).toBe('22 Oct 2026 · AWS Community Day Adria')
    expect(sessionName({ ...session, name: null })).toBe('Kino Šiška')
    expect(sessionTitle({ ...session, name: null })).toBe('22 Oct 2026 · Kino Šiška')
    expect(sessionName({ ...session, name: null, location: null })).toBeNull()
    expect(sessionTitle({ ...session, name: null, location: null })).toBe('22 Oct 2026')
  })

  it('says where it is only when its name doesn\'t', () => {
    expect(sessionPlace(session)).toBe('Kino Šiška')
    // As every session was named after its location to begin with
    expect(sessionPlace({ ...session, name: 'Kino Šiška' })).toBeNull()
    expect(sessionPlace({ ...session, name: null })).toBeNull()
    expect(sessionPlace({ ...session, location: null })).toBeNull()
  })
})
