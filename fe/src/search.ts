// Search as MetaOS's GraphQL API has it (src/main/resources/graphql/schema.graphqls, Search.kt): episodes, ideas too,
// people and recording sessions. It's the team's, so only signed-in users can search.

import { EPISODE_FIELDS, type Episode } from '@/episodes'
import { type Person, PERSON_FIELDS } from '@/people'

/** Where a search matched */
export type SearchField =
  | 'CODE'
  | 'TITLE'
  | 'ALTERNATIVE_TITLE'
  | 'PEOPLE'
  | 'SHOW'
  | 'TINY_SUMMARY'
  | 'SUMMARY'
  | 'SESSION'
  | 'NAME'
  | 'BIO'
  | 'LOCATION'
  | 'ADDRESS'
  | 'DATE'

export interface EpisodeHit {
  episode: Episode
  // The best first
  matchedIn: SearchField[]
  // The alternative title or the part of a summary it matched in, which its row wouldn't show otherwise
  snippet: string | null
}

export interface PersonHit {
  person: Person
  matchedIn: SearchField[]
}

/** A session as search results list it: when and where, and its episodes by code */
export interface FoundSession {
  id: string
  date: string
  name: string | null
  location: string | null
  address: string | null
  episodes: Pick<Episode, 'id' | 'code' | 'title'>[]
}

export interface SessionHit {
  session: FoundSession
  matchedIn: SearchField[]
}

/** The episode whose code the text is, alone; a season's episodes; or the best of each kind, and how many there are */
export interface SearchResults {
  episode: Episode | null
  season: number | null
  episodes: EpisodeHit[]
  people: PersonHit[]
  sessions: SessionHit[]
  episodeCount: number
  personCount: number
  sessionCount: number
}

export const SEARCH_QUERY = `query ($text: String!, $limit: Int!) {
  search(text: $text, limit: $limit) {
    episode { ${EPISODE_FIELDS} }
    season
    episodes { episode { ${EPISODE_FIELDS} } matchedIn snippet }
    people { person { ${PERSON_FIELDS} } matchedIn }
    sessions { session { id date name location address episodes { id code title } } matchedIn }
    episodeCount personCount sessionCount
  }
}`

/** How many there are in all: the episode whose code it was counts as one */
export function resultCount(results: SearchResults) {
  return (results.episode ? 1 : 0) + results.episodeCount + results.personCount + results.sessionCount
}

/** The search page, for a text */
export function searchPath(text: string) {
  return { path: '/search', query: { q: text } }
}

// What a row says before a snippet, by where it's from
const SNIPPET_LABELS: Partial<Record<SearchField, string>> = {
  ALTERNATIVE_TITLE: 'Also titled',
  TINY_SUMMARY: 'From the short summary',
  SUMMARY: 'From the summary',
}

/** Why a search found an episode, for its row: what to mark, and a line the row wouldn't show otherwise */
export interface EpisodeMatch {
  words: string[]
  label: string | null
  text: string | null
}

/**
 * Why a search found an episode, as its row says it when it doesn't show that already: another title, a bit of a
 * summary, or the people on it whose names matched
 */
export function episodeMatch(hit: EpisodeHit, words: string[], shown: { tinySummary?: boolean, guests?: boolean } = {}): EpisodeMatch {
  // The snippet is from the best of the fields it can be from, which matchedIn has first
  const source = hit.matchedIn.find((field) => SNIPPET_LABELS[field])
  if (hit.snippet && source && !(source === 'TINY_SUMMARY' && shown.tinySummary)) {
    return { words, label: SNIPPET_LABELS[source] ?? null, text: hit.snippet }
  }
  if (hit.matchedIn.includes('PEOPLE')) {
    const named = hit.episode.people
      .filter((person) => markWords(person.name, words).some((part) => part.marked))
      .filter((person) => !(shown.guests && person.roles.includes('GUEST')))
    if (named.length) return { words, label: 'With', text: named.map((person) => person.name).join(', ') }
  }
  return { words, label: null, text: null }
}

const MARKS = /\p{M}/gu
const NOT_WORD = /[^\p{L}\p{N}]+/u
const WORD = /[\p{L}\p{N}]/u

/** Lower case and without diacritics, as search reads text (Search.kt's fold): Čuhalev is cuhalev, đ is d */
export function fold(text: string) {
  return text.normalize('NFD').replace(MARKS, '').toLowerCase().replaceAll('đ', 'd')
}

/** The words of a text, folded, as search looks for them */
export function searchWords(text: string) {
  return fold(text).split(NOT_WORD).filter(Boolean)
}

/** A piece of a text, and whether it's one of the words searched for */
export interface TextPart {
  text: string
  marked: boolean
}

/**
 * A text in pieces, with the words marked where search would find them: at the start of a word, or inside one when
 * they're three letters or more
 */
export function markWords(text: string, words: string[]): TextPart[] {
  // Folded a character at a time, with where in the text each folded one came from
  let folded = ''
  const from: number[] = []
  for (let i = 0; i < text.length; i++) {
    for (const char of fold(text.charAt(i))) {
      folded += char
      from.push(i)
    }
  }
  from.push(text.length)

  const ranges: [number, number][] = []
  for (const word of words) {
    for (let at = folded.indexOf(word); at >= 0; at = folded.indexOf(word, at + 1)) {
      if (at === 0 || !WORD.test(folded.charAt(at - 1)) || word.length >= 3) {
        ranges.push([from[at] ?? 0, (from[at + word.length - 1] ?? text.length - 1) + 1])
      }
    }
  }
  ranges.sort((a, b) => a[0] - b[0])

  const parts: TextPart[] = []
  let at = 0
  for (const [start, end] of ranges) {
    if (end <= at) continue
    if (start > at) parts.push({ text: text.slice(at, start), marked: false })
    parts.push({ text: text.slice(Math.max(start, at), end), marked: true })
    at = end
  }
  if (at < text.length) parts.push({ text: text.slice(at), marked: false })
  return parts
}
