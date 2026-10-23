// What on the home page needs the team's attention: episodes whose recording date passed while they're still to be
// recorded, recordings coming up without what they need, and episodes long in production

import { candidates, daysFrom, type Episode, type EpisodeStatus, guests, statusName } from '@/episodes'

// How far ahead a recording should have its guest confirmed and a doc to prepare from
export const SOON_DAYS = 14
// How long after its recording an episode that isn't out yet is worth asking about
export const LONG_IN_PRODUCTION_DAYS = 60

export interface Concern {
  episode: Episode
  // What's wrong, as a few words each, e.g. "Guest not confirmed"
  reasons: string[]
  // The date it's about, its recording's
  date: string | null
  // Past recording dates first, as they're most likely wrong, then the soonest recording, then the longest in production
  rank: number
}

/** When an episode is or was recorded: its own date, or its session's */
export function recordingDay(episode: Pick<Episode, 'recordingDate' | 'session'>) {
  return episode.recordingDate ?? episode.session?.date ?? null
}

function hasDoc(episode: Episode) {
  return episode.documents.length > 0 || (episode.session?.documents.length ?? 0) > 0
}

/** What needs attention of [upcoming] (upcomingRecordings) and [inProduction], as of [today] */
export function concerns(upcoming: Episode[], inProduction: Episode[], today = new Date()): Concern[] {
  const found: Concern[] = []

  for (const episode of upcoming) {
    const date = recordingDay(episode)
    if (!date) continue
    const days = daysFrom(today, date)
    const reasons: string[] = []
    if (days < 0) {
      reasons.push(`Recording date passed, still ${statusName(episode.status).toLowerCase()}`)
    } else if (days <= SOON_DAYS) {
      // Only when someone's asked: some episodes are the hosts' own
      if (candidates(episode).length && !guests(episode).length) reasons.push('Guest not confirmed')
      if (!hasDoc(episode)) reasons.push('No doc')
    }
    if (reasons.length) found.push({ episode, reasons, date, rank: days < 0 ? days - 100_000 : days })
  }

  for (const episode of inProduction) {
    const date = recordingDay(episode)
    if (!date) continue
    const days = -daysFrom(today, date)
    if (days >= LONG_IN_PRODUCTION_DAYS) {
      found.push({ episode, reasons: [`Recorded ${days} days ago, not released yet`], date, rank: 100_000 - days })
    }
  }

  return found.sort((a, b) => a.rank - b.rank)
}

// Still to be recorded, as upcomingRecordings has them, and being made, as inProduction has them (GraphQL.kt)
const TO_RECORD: EpisodeStatus[] = ['IDEA', 'PLANNED', 'SCHEDULED']
const BEING_MADE: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS']

/** What needs attention of one [episode], e.g. on its page, by the same rules as the home page's */
export function episodeConcerns(episode: Episode, today = new Date()): string[] {
  const upcoming = TO_RECORD.includes(episode.status) ? [episode] : []
  const inProduction = BEING_MADE.includes(episode.status) ? [episode] : []
  return concerns(upcoming, inProduction, today).flatMap((concern) => concern.reasons)
}
