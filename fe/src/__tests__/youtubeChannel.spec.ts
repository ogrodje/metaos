import { describe, expect, it } from 'vitest'
import {
  type ChannelUpload, type ChannelVideo, detailsRefusal, filterVideos, kindOf, shownUploads, tagsOf, tagsText, videoLine,
  type VideoFilters,
} from '@/youtubeChannel'
import { t, tSl } from './i18n'

const video = (videoId: string, change: Partial<ChannelVideo> = {}): ChannelVideo => ({
  id: `id-${videoId}`, videoId, url: `https://www.youtube.com/watch?v=${videoId}`, studioUrl: `https://studio.youtube.com/video/${videoId}/edit`,
  title: videoId, description: '', tags: [], privacy: 'PUBLIC', uploadStatus: 'processed', publishAt: null,
  publishedAt: '2026-10-20T08:00:00Z', duration: 3600, live: false, thumbnailUrl: null, episode: null, mediaId: null, ...change,
})

const upload = (id: string, change: Partial<ChannelUpload> = {}): ChannelUpload => ({
  id, episode: null, mediaId: 'file', mediaName: 'S05E08.mp4', title: 'Poletna vročica', status: 'DONE', queued: null, percent: null,
  error: null, note: null, videoId: null, video: null, requestedBy: null, createdAt: '2026-11-24T10:00:00Z', ...change,
})

const none: VideoFilters = { kind: 'all', privacy: 'all', episode: 'all', find: '' }

describe('the channel\'s videos', () => {
  it('takes a short one that isn\'t a stream for a Short', () => {
    expect(kindOf(video('a', { duration: 40 }))).toBe('short')
    expect(kindOf(video('a', { duration: 180 }))).toBe('short')
    expect(kindOf(video('a', { duration: 181 }))).toBe('video')
    expect(kindOf(video('a', { duration: 40, live: true }))).toBe('video')
    // Not measured yet, as YouTube is still processing it
    expect(kindOf(video('a', { duration: null }))).toBe('video')
  })

  it('filters by kind, who may see them, whose they are, and what they say', () => {
    const episode = { id: 'e', code: 'S05E07', title: 'Domen Vreš in GaMS' }
    const videos = [
      video('full', { title: 'Domen Vreš in jezikovni model', episode }),
      video('short', { title: 'Domen Vreš o GaMS-u #ogrodje', duration: 29, tags: ['slovenščina'] }),
      video('draft', { title: 'S05E12 NTK 3', privacy: 'PRIVATE' }),
      video('hidden', { title: 'Pozdrav iz Portoroža!', privacy: 'UNLISTED', duration: 879 }),
    ]
    const ids = (filters: Partial<VideoFilters>) => filterVideos(videos, { ...none, ...filters }).map((each) => each.videoId)
    expect(ids({})).toEqual(['full', 'short', 'draft', 'hidden'])
    expect(ids({ kind: 'short' })).toEqual(['short'])
    expect(ids({ kind: 'video', privacy: 'private' })).toEqual(['draft'])
    expect(ids({ privacy: 'unlisted' })).toEqual(['hidden'])
    expect(ids({ episode: 'with' })).toEqual(['full'])
    expect(ids({ episode: 'without', kind: 'video' })).toEqual(['draft', 'hidden'])
    // By its title, its episode's code, a tag, whatever the case and diacritics, or YouTube's ID
    expect(ids({ find: 'vres' })).toEqual(['full', 'short'])
    expect(ids({ find: 's05e07' })).toEqual(['full'])
    expect(ids({ find: 'slovenscina' })).toEqual(['short'])
    expect(ids({ find: 'hidden' })).toEqual(['hidden'])
  })

  it('says what a video is in a line', () => {
    expect(videoLine(video('a'), t, 'en-GB')).toBe('1:00:00 · 20 Oct 2026')
    expect(videoLine(video('a', { duration: 29 }), t, 'en-GB')).toBe('Short · 0:29 · 20 Oct 2026')
    expect(videoLine(video('a', { live: true, duration: 7360 }), tSl, 'sl-SI')).toBe('Prenos v živo · 2:02:40 · 20. okt. 2026')
    expect(videoLine(video('a', { duration: null, publishedAt: null, uploadStatus: 'uploaded' }), t, 'en-GB')).toBe('YouTube: uploaded')
  })
})

describe('a video\'s details', () => {
  it('reads tags as they\'re typed', () => {
    expect(tagsOf(' ogrodje, #podcast ,, AI agenti, ogrodje ')).toEqual(['ogrodje', 'podcast', 'AI agenti'])
    expect(tagsText(['ogrodje', 'AI agenti'])).toBe('ogrodje, AI agenti')
    expect(tagsOf('')).toEqual([])
  })

  it('refuses what YouTube wouldn\'t take', () => {
    const details = { title: 'Poletna vročica', description: '0:00 Uvod', tags: ['ogrodje'] }
    expect(detailsRefusal(details, t)).toBeNull()
    expect(detailsRefusal({ ...details, title: '  ' }, t)).toBe('A video needs a title')
    expect(detailsRefusal({ ...details, title: 'a'.repeat(101) }, t)).toBe('A title is at most 100 characters')
    expect(detailsRefusal({ ...details, description: 'a <b>' }, t)).toBe('A title and a description can\'t have < or >')
    // Bytes, not characters: č is two
    expect(detailsRefusal({ ...details, description: 'č'.repeat(2500) }, t)).toBeNull()
    expect(detailsRefusal({ ...details, description: 'č'.repeat(2501) }, t)).toBe('A description is at most 5000 bytes')
    // A comma between tags, and quotes around one with a space
    expect(detailsRefusal({ ...details, tags: ['a'.repeat(249), 'b'.repeat(250)] }, t)).toBeNull()
    expect(detailsRefusal({ ...details, tags: ['a'.repeat(249), `${'b'.repeat(124)} ${'b'.repeat(124)}`] }, t))
      .toBe('Tags are at most 500 characters in all')
  })
})

describe('the uploads the page shows', () => {
  it('are those on, then the latest few that ended', () => {
    const uploads = [
      upload('old', { createdAt: '2026-11-01T10:00:00Z' }),
      upload('failed', { status: 'FAILED', createdAt: '2026-11-20T10:00:00Z' }),
      upload('sending', { status: 'UPLOADING', percent: 40, createdAt: '2026-11-24T10:00:00Z' }),
      upload('waiting', { status: 'QUEUED', queued: 1, createdAt: '2026-11-25T10:00:00Z' }),
      upload('done', { createdAt: '2026-11-22T10:00:00Z' }),
    ]
    const shown = shownUploads(uploads, 2)
    expect(shown.on.map((each) => each.id)).toEqual(['waiting', 'sending'])
    expect(shown.ended.map((each) => each.id)).toEqual(['done', 'failed'])
  })
})
