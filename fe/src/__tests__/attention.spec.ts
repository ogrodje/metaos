import { describe, expect, it } from 'vitest'
import { concerns, DISTRIBUTED_DAYS, episodeConcerns, LONG_IN_PRODUCTION_DAYS, SOON_DAYS } from '@/attention'
import type { Episode, EpisodePerson, EpisodeStatus, Session } from '@/episodes'

const today = new Date(2026, 9, 23)

function session(date: string, documents = 0): Session {
  return {
    id: `s-${date}`, date, name: null, location: null, koofrFolders: [], dropboxFolders: [],
    documents: Array.from({ length: documents }, (_, i) => ({ fileId: `d${i}`, url: '' })),
  }
}

function episode(id: string, status: EpisodeStatus, fields: Partial<Episode> = {}): Episode {
  return {
    id, code: id, title: id, show: null, showColor: null, status, session: null, recordingDate: null, airedDate: null,
    youtubeUrl: null, spotifyUrl: null, applePodcastsUrl: null, patreonUrl: null, tinySummary: null, people: [],
    documents: [{ fileId: 'doc', url: '' }], koofrFolders: [], dropboxFolders: [], ...fields,
  }
}

const candidate: EpisodePerson = { personId: 'p1', name: 'Ana', roles: ['CANDIDATE'] }
const guest: EpisodePerson = { personId: 'p2', name: 'Bor', roles: ['GUEST'] }

describe('concerns', () => {
  it('flags a recording whose date passed while it is still to be recorded', () => {
    const [concern] = concerns([episode('S5E9', 'SCHEDULED', { session: session('2026-10-22') })], [], today)
    expect(concern?.reasons).toEqual(['Recording date passed, still scheduled'])
  })

  it("doesn't flag a recording today", () => {
    expect(concerns([episode('S5E9', 'SCHEDULED', { recordingDate: '2026-10-23' })], [], today)).toEqual([])
  })

  it('flags a recording soon with someone asked but no guest confirmed, and without a doc', () => {
    const soon = episode('S5E10', 'PLANNED', { recordingDate: '2026-10-30', people: [candidate], documents: [] })
    expect(concerns([soon], [], today)[0]?.reasons).toEqual(['Guest not confirmed', 'No doc'])
  })

  it("takes the session's doc for the episode's, and doesn't ask for a guest no one was asked to be", () => {
    const soon = episode('S5E10', 'PLANNED', { session: session('2026-10-30', 1), documents: [] })
    expect(concerns([soon], [], today)).toEqual([])
  })

  it('is fine with a confirmed guest', () => {
    const soon = episode('S5E10', 'PLANNED', { recordingDate: '2026-10-30', people: [candidate, guest] })
    expect(concerns([soon], [], today)).toEqual([])
  })

  it(`leaves recordings more than ${SOON_DAYS} days away alone`, () => {
    const later = episode('S5E11', 'PLANNED', { recordingDate: '2026-11-20', people: [candidate], documents: [] })
    expect(concerns([later], [], today)).toEqual([])
  })

  it(`flags an episode recorded ${LONG_IN_PRODUCTION_DAYS} days ago or more that isn't out`, () => {
    const old = episode('S5E8', 'IN_PRODUCTION', { session: session('2026-07-09') })
    const recent = episode('S5E7', 'IN_PRODUCTION', { recordingDate: '2026-10-01' })
    const found = concerns([], [old, recent], today)
    expect(found.map((concern) => concern.reasons)).toEqual([['Recorded 106 days ago, not released yet']])
  })

  it('lists past recording dates first, then the soonest recordings, then the longest in production', () => {
    const found = concerns([
      episode('soon', 'PLANNED', { recordingDate: '2026-10-30', documents: [] }),
      episode('sooner', 'PLANNED', { recordingDate: '2026-10-25', documents: [] }),
      episode('past', 'SCHEDULED', { recordingDate: '2026-10-20' }),
    ], [
      episode('old', 'READY', { recordingDate: '2026-08-01' }),
      episode('older', 'IN_PRODUCTION', { recordingDate: '2026-06-01' }),
    ], today)
    expect(found.map((concern) => concern.episode.id)).toEqual(['past', 'sooner', 'soon', 'older', 'old'])
  })
})

describe('episodeConcerns', () => {
  it('tells what needs attention of one episode, by its status, as the home page would', () => {
    expect(episodeConcerns(episode('S5E8', 'IN_PRODUCTION', { recordingDate: '2026-07-09' }), today))
      .toEqual(['Recorded 106 days ago, not released yet'])
    expect(episodeConcerns(episode('S5E9', 'SCHEDULED', { recordingDate: '2026-10-22' }), today))
      .toEqual(['Recording date passed, still scheduled'])
    expect(episodeConcerns(episode('S5E7', 'PUBLISHED', { recordingDate: '2026-07-09' }), today)).toEqual([])
  })
})

describe('released episodes', () => {
  it(`flags one released ${DISTRIBUTED_DAYS} days ago or more that isn't on YouTube or Patreon`, () => {
    const out = episode('S5E1', 'PUBLISHED', { airedDate: '2026-10-20', spotifyUrl: 'https://open.spotify.com/episode/1' })
    expect(concerns([], [], today, [out])[0]?.reasons).toEqual(['Released 3 days ago, not on YouTube or Patreon'])
    expect(episodeConcerns(out, today)).toEqual(['Released 3 days ago, not on YouTube or Patreon'])
  })

  it("doesn't flag one that's on YouTube or Patreon, by its links or its distribution, or released lately", () => {
    const aired = { airedDate: '2026-10-01' }
    expect(concerns([], [], today, [
      episode('S5E2', 'PUBLISHED', { ...aired, youtubeUrl: 'https://youtu.be/a' }),
      episode('S5E3', 'PUBLISHED', { ...aired, patreonUrl: 'https://www.patreon.com/posts/1' }),
      { ...episode('S5E4', 'PUBLISHED', aired), distributions: [{ channel: 'PATREON' }] },
      episode('S5E5', 'PUBLISHED', { airedDate: '2026-10-21' }),
      episode('S5E6', 'PUBLISHED'),
    ])).toEqual([])
    // Early access took a Patreon link
    expect(episodeConcerns(episode('S5E7', 'EARLY_ACCESS', { ...aired, recordingDate: '2026-10-01' }), today)).toEqual([])
  })
})
