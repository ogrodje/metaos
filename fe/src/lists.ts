// What the list pages (Episodes, Ideas, People) filter and group by: as pure functions, apart from the pages, so they
// can be tested, and the pages filter in the browser on what they've loaded already

import { candidates, type Episode, guests, type Role } from '@/episodes'
import type { Person } from '@/people'
import { searchWords } from '@/search'

/** Whether [text] has every word of [query] in it, at a word's start or inside one, whatever the case or diacritics */
export function matches(text: string, query: string) {
  const wanted = searchWords(query)
  if (!wanted.length) return true
  const words = searchWords(text)
  return wanted.every((word) => words.some((w) => w.includes(word)))
}

/** What an episode is found by in a list: its code, titles, show and people */
export function episodeText(episode: Pick<Episode, 'code' | 'title' | 'show' | 'people'>) {
  return [episode.code, episode.title, episode.show, ...episode.people.map((person) => person.name)].filter(Boolean).join(' ')
}

/** The season in an episode's code, e.g. 5 for S05E08; 99 is the bonus season; null without a code */
export function seasonOf(episode: Pick<Episode, 'code'>): number | null {
  const season = episode.code?.match(/^S(\d+)E/i)?.[1]
  return season ? Number(season) : null
}

export function seasonTitle(season: number | null) {
  if (season === null) return 'Without a code'
  return season === 99 ? 'Bonus' : `Season ${season}`
}

/** How far along an idea is: in a recording session, with a confirmed guest, with candidates only, or with no one yet */
export type Readiness = 'session' | 'guest' | 'candidates' | 'none'

export function readiness(idea: Pick<Episode, 'session' | 'recordingDate' | 'people'>): Readiness {
  if (idea.session || idea.recordingDate) return 'session'
  if (guests(idea).length) return 'guest'
  if (candidates(idea).length) return 'candidates'
  return 'none'
}

export const READINESS: { id: Readiness, title: string }[] = [
  { id: 'session', title: 'In a session' },
  { id: 'guest', title: 'With a guest' },
  { id: 'candidates', title: 'With candidates' },
  { id: 'none', title: 'No one yet' },
]

// What makes someone the team's on the People page, until profiles say so themselves
const TEAM_ROLES: Role[] = ['HOST', 'CO_HOST', 'PRODUCER', 'CONSULTANT', 'DESIGNER']

/** Who someone is on the People page: on the team, a guest, or only a candidate so far (whom only the team sees) */
export type Kind = 'team' | 'guest' | 'candidate'

export function kindOf(person: Pick<Person, 'episodes'>): Kind {
  const roles = new Set(person.episodes.flatMap((episode) => episode.roles))
  if (TEAM_ROLES.some((role) => roles.has(role))) return 'team'
  if (roles.size && [...roles].every((role) => role === 'CANDIDATE')) return 'candidate'
  return 'guest'
}

/** The letter a person is listed under, past a "dr.", e.g. B for dr. Borka Jerman Blažič */
export function letterOf(name: string) {
  return name.replace(/^dr\.\s+/i, '').charAt(0).toLocaleUpperCase('sl')
}

/** The Slovenian alphabet, for the People page's index */
export const ALPHABET = 'ABCČDEFGHIJKLMNOPRSŠTUVZŽ'.split('')

/** How many episodes someone's been on as each role, the most first, e.g. host 46, co-host 5 */
export function roleCounts(person: Pick<Person, 'episodes'>): { role: Role, count: number }[] {
  const counts = new Map<Role, number>()
  for (const { roles } of person.episodes) for (const role of roles) counts.set(role, (counts.get(role) ?? 0) + 1)
  return [...counts].map(([role, count]) => ({ role, count })).sort((a, b) => b.count - a.count)
}
