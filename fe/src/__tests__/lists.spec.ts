import { describe, expect, it } from 'vitest'
import type { Episode, EpisodePerson } from '@/episodes'
import { episodeText, kindOf, letterOf, matches, readiness, roleCounts, seasonOf } from '@/lists'

function episode(fields: Partial<Episode> = {}): Episode {
  return {
    id: 'e', code: null, title: 'Idea', show: null, showColor: null, status: 'IDEA', session: null, recordingDate: null,
    airedDate: null, youtubeUrl: null, spotifyUrl: null, applePodcastsUrl: null, patreonUrl: null, tinySummary: null,
    people: [], documents: [], koofrFolders: [], dropboxFolders: [], ...fields,
  }
}

const person = (name: string, ...roles: EpisodePerson['roles']): EpisodePerson => ({ personId: name, name, roles })

describe('matches', () => {
  it('finds every word, anywhere in a word, whatever the case or diacritics', () => {
    const text = episodeText(episode({ code: 'S05E07', title: 'Domen Vreš in jezikovni model GaMS', people: [person('Domen Vreš', 'GUEST')] }))
    expect(matches(text, 'vres gams')).toBe(true)
    expect(matches(text, 's05e07')).toBe(true)
    expect(matches(text, 'jezik')).toBe(true)
    expect(matches(text, 'vres kotlin')).toBe(false)
    expect(matches(text, '  ')).toBe(true)
  })
})

describe('seasonOf', () => {
  it('reads the season from the code', () => {
    expect(seasonOf({ code: 'S05E08' })).toBe(5)
    expect(seasonOf({ code: 'S99E02' })).toBe(99)
    expect(seasonOf({ code: null })).toBe(null)
  })
})

describe('readiness', () => {
  it('tells how far along an idea is', () => {
    expect(readiness(episode({ recordingDate: '2026-12-01' }))).toBe('session')
    expect(readiness(episode({ people: [person('Larsen Cundrič', 'GUEST'), person('Frenk Dragar', 'HOST')] }))).toBe('guest')
    expect(readiness(episode({ people: [person('Gavin King', 'CANDIDATE')] }))).toBe('candidates')
    expect(readiness(episode({ people: [person('Andraž Sraka', 'HOST')] }))).toBe('none')
  })
})

describe('people', () => {
  const on = (...roles: EpisodePerson['roles'][]) => ({ episodes: roles.map((r) => ({ episode: { id: 'e', code: null, title: 'E' }, roles: r })) })

  it('tells the team from guests and candidates', () => {
    expect(kindOf(on(['GUEST'], ['PRODUCER']))).toBe('team')
    expect(kindOf(on(['GUEST'], ['CANDIDATE']))).toBe('guest')
    expect(kindOf(on(['CANDIDATE']))).toBe('candidate')
    expect(kindOf(on())).toBe('guest')
  })

  it('counts roles, the most first', () => {
    expect(roleCounts(on(['HOST'], ['HOST', 'CONSULTANT'], ['GUEST'], ['HOST']))).toEqual([
      { role: 'HOST', count: 3 }, { role: 'CONSULTANT', count: 1 }, { role: 'GUEST', count: 1 },
    ])
  })

  it('lists people by the letter of their name, past a title', () => {
    expect(letterOf('dr. Borka Jerman Blažič')).toBe('B')
    expect(letterOf('Črt Kristl')).toBe('Č')
  })
})
