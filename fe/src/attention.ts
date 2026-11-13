// What on the home page needs the team's attention: episodes whose recording date passed while they're still to be
// recorded, recordings coming up without what they need, episodes long in production, and released episodes that
// aren't on YouTube or Patreon a few days on

import { candidates, daysFrom, type Episode, type EpisodeStatus, guests, statusName } from '@/episodes'
import type { Translate } from '@/i18n'

// How far ahead a recording should have its guest confirmed and a doc to prepare from
export const SOON_DAYS = 14
// How long after its recording an episode that isn't out yet is worth asking about
export const LONG_IN_PRODUCTION_DAYS = 60
// How long after it's released an episode should be on YouTube or Patreon
export const DISTRIBUTED_DAYS = 3

export interface Concern {
  episode: Episode
  // What's wrong, as a few words each, in the page's language, e.g. "Guest not confirmed"
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

// Where it went out, where the query asked for it (DISTRIBUTION_FIELDS): only the channels
type Distributed = Episode & { distributions?: { channel: string }[] }

/**
 * Whether a released episode is on YouTube or Patreon: by its links, or what its distribution says. One in early access
 * is, as it took a Patreon link to get there (EpisodesMCP).
 */
export function onYouTubeOrPatreon(episode: Distributed) {
  return episode.status === 'EARLY_ACCESS' || !!episode.youtubeUrl || !!episode.patreonUrl ||
    !!episode.distributions?.some((entry) => entry.channel === 'YOUTUBE' || entry.channel === 'PATREON')
}

/**
 * What needs attention of [upcoming] (upcomingRecordings), [inProduction] and [released] (e.g. the latest releases), as
 * of [today], said with [t]
 */
export function concerns(
  upcoming: Episode[], inProduction: Episode[], t: Translate, today = new Date(), released: Distributed[] = [],
): Concern[] {
  const found: Concern[] = []

  for (const episode of upcoming) {
    const date = recordingDay(episode)
    if (!date) continue
    const days = daysFrom(today, date)
    const reasons: string[] = []
    if (days < 0) {
      reasons.push(t('attention.passed', { status: statusName(episode.status, t).toLowerCase() }))
    } else if (days <= SOON_DAYS) {
      // Only when someone's asked: some episodes are the hosts' own
      if (candidates(episode).length && !guests(episode).length) reasons.push(t('attention.guestNotConfirmed'))
      if (!hasDoc(episode)) reasons.push(t('attention.noDoc'))
    }
    if (reasons.length) found.push({ episode, reasons, date, rank: days < 0 ? days - 100_000 : days })
  }

  for (const episode of inProduction) {
    const date = recordingDay(episode)
    if (!date) continue
    const days = -daysFrom(today, date)
    if (days >= LONG_IN_PRODUCTION_DAYS) {
      found.push({ episode, reasons: [t('attention.notReleased', { n: days }, days)], date, rank: 100_000 - days })
    }
  }

  for (const episode of released) {
    if (episode.status !== 'PUBLISHED' || !episode.airedDate || onYouTubeOrPatreon(episode)) continue
    const days = -daysFrom(today, episode.airedDate)
    if (days >= DISTRIBUTED_DAYS) {
      found.push({ episode, reasons: [t('attention.notDistributed', { n: days }, days)], date: episode.airedDate, rank: 50_000 - days })
    }
  }

  return found.sort((a, b) => a.rank - b.rank)
}

// Still to be recorded, as upcomingRecordings has them, and being made, as inProduction has them (GraphQL.kt)
const TO_RECORD: EpisodeStatus[] = ['IDEA', 'PLANNED', 'SCHEDULED']
const BEING_MADE: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS']

/** What needs attention of one [episode], e.g. on its page, by the same rules as the home page's, said with [t] */
export function episodeConcerns(episode: Distributed, t: Translate, today = new Date()): string[] {
  const upcoming = TO_RECORD.includes(episode.status) ? [episode] : []
  const inProduction = BEING_MADE.includes(episode.status) ? [episode] : []
  const released = episode.status === 'PUBLISHED' ? [episode] : []
  return concerns(upcoming, inProduction, t, today, released).flatMap((concern) => concern.reasons)
}
