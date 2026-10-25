// Ideas' votes (Votes.kt): the team votes an idea up or down, once each, which shows how much it wants it recorded.
// They're the team's: anyone else gets a score of 0 and no voters.

import type { Person } from '@/people'

export type Vote = 'UP' | 'DOWN' | 'NONE'

/** Who voted which way */
export interface Voter {
  // Their person's name, or else their username
  name: string
  username: string | null
  person: Pick<Person, 'id' | 'name'> | null
  vote: Vote
}

/** An idea's votes: ups minus downs, how many each, your own, and who voted, ups first */
export interface Votes {
  score: number
  up: number
  down: number
  mine: Vote
  voters: Voter[]
}

const FIELDS = 'score up down mine voters { name username person { id name } vote }'

/** An episode's votes, for a query */
export const VOTE_FIELDS = `votes { ${FIELDS} }`

/** Votes on an idea, by its ID, which stays while its code changes, answering with its votes as `votes` */
export const VOTE_ON_IDEA = `mutation ($ref: String!, $vote: Vote!) { votes: voteOnIdea(ref: $ref, vote: $vote) { ${FIELDS} } }`

/** The vote a click on [clicked] casts: it, or none when it's your vote already, which takes it back */
export function toggled(mine: Vote, clicked: Exclude<Vote, 'NONE'>): Vote {
  return mine === clicked ? 'NONE' : clicked
}

/** A score as it's shown, with its sign, e.g. +4, 0, -1 */
export function scoreText(score: number) {
  return score > 0 ? `+${score}` : String(score)
}

/** Who voted, for a tooltip, e.g. ▲ 2 Oto, Maja · ▼ 1 Luka; or that nobody did */
export function votersText(votes: Votes) {
  const names = (vote: Vote) => votes.voters.filter((voter) => voter.vote === vote).map((voter) => voter.name)
  const ups = names('UP')
  const downs = names('DOWN')
  if (!ups.length && !downs.length) return 'No votes yet'
  return [ups.length ? `▲ ${ups.length} ${ups.join(', ')}` : '', downs.length ? `▼ ${downs.length} ${downs.join(', ')}` : '']
    .filter(Boolean).join(' · ')
}

/** Ideas the most wanted first: by score, then by more ups, keeping their order otherwise, e.g. the newest first */
export function byVotes<T extends { votes: Votes }>(ideas: T[]): T[] {
  return [...ideas].sort((a, b) => b.votes.score - a.votes.score || b.votes.up - a.votes.up)
}
