import { describe, expect, it } from 'vitest'
import { fileOnYouTube, isOn, uploadLine, type YouTubeUpload, type YouTubeVideo } from '@/youtube'
import { t, tSl } from './i18n'

const video = (videoId: string, mediaId: string | null): YouTubeVideo => ({
  id: `id-${videoId}`, videoId, title: videoId, privacy: 'PRIVATE', uploadStatus: 'processed',
  url: `https://www.youtube.com/watch?v=${videoId}`, studioUrl: `https://studio.youtube.com/video/${videoId}/edit`, mediaId,
})

const upload = (id: string, change: Partial<YouTubeUpload> = {}): YouTubeUpload => ({
  id, mediaId: 'file', status: 'DONE', queued: null, percent: null, error: null, note: null, videoId: null,
  createdAt: '2026-11-24T10:00:00Z', ...change,
})

describe('what YouTube has of an episode\'s file', () => {
  it('is the videos made of it, and its upload only while that says something', () => {
    const videos = [video('made', 'file'), video('linked', null), video('other', 'another')]
    // Done, with its video there: the video says it
    expect(fileOnYouTube('file', videos, [upload('1', { videoId: 'made' })])).toEqual({ videos: [videos[0]], upload: undefined })
    // On, the latest of its uploads
    const on = upload('2', { status: 'UPLOADING', percent: 40, createdAt: '2026-11-24T12:00:00Z' })
    expect(fileOnYouTube('file', videos, [upload('1', { videoId: 'made' }), on]).upload).toBe(on)
    // Failed, or done without its thumbnail
    const failed = upload('3', { status: 'FAILED', error: 'YouTube answered 400' })
    expect(fileOnYouTube('file', [], [failed])).toEqual({ videos: [], upload: failed })
    const noted = upload('4', { videoId: 'made', note: 'Its thumbnail wasn\'t set' })
    expect(fileOnYouTube('file', videos, [noted]).upload).toBe(noted)
    // Another file's are another's
    expect(fileOnYouTube('nothing', videos, [on])).toEqual({ videos: [], upload: undefined })
  })

  it('says how far an upload is', () => {
    expect(isOn(upload('1', { status: 'PROCESSING' }))).toBe(true)
    expect(isOn(upload('1', { status: 'CANCELLED' }))).toBe(false)
    expect(uploadLine(upload('1', { status: 'QUEUED', queued: 2 }), t)).toBe('Waiting its turn, 2 in line')
    expect(uploadLine(upload('1', { status: 'QUEUED' }), t)).toBe('Waiting its turn')
    expect(uploadLine(upload('1', { status: 'UPLOADING', percent: 40 }), t)).toBe('Sending to YouTube, 40 %')
    expect(uploadLine(upload('1', { status: 'UPLOADING', percent: 40 }), tSl)).toBe('Pošiljanje na YouTube, 40 %')
    expect(uploadLine(upload('1', { status: 'PROCESSING' }), t)).toBe('On YouTube, which is processing it')
    expect(uploadLine(upload('1', { status: 'FAILED', error: 'YouTube answered 400' }), t)).toBe('It didn\'t get to YouTube: YouTube answered 400')
    expect(uploadLine(upload('1', { note: 'Its thumbnail wasn\'t set' }), t)).toBe('Sent, but: Its thumbnail wasn\'t set')
  })
})
