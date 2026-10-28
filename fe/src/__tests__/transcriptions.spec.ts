import { describe, expect, it } from 'vitest'
import {
  defaultFile, formatWhen, hasTranscript, languageOf, isOn, ordinal, providerName, speakerName, split, statusText, timestamp, took, type Transcription,
  transcriptSummary,
} from '@/transcriptions'

function transcription(status: Transcription['status'], more: Partial<Transcription> = {}): Transcription {
  return {
    id: `${status}-${more.createdAt ?? ''}`, episode: { id: 'e', code: 'S05E07', title: 'GaMS' }, mediaId: 'm', mediaName: 'S05E07.mp3',
    provider: 'soniox', model: 'stt-async-v5', language: 'sl-SI', batch: false, status, queued: null, percent: null, error: null,
    transcript: null, requestedBy: 'Oto', createdAt: '2026-10-28T10:00:00Z', startedAt: null, finishedAt: null, ...more,
  }
}

describe('transcriptions', () => {
  it('say how far they are, a place in line too', () => {
    expect(statusText(transcription('QUEUED', { queued: 1 }))).toBe('Next in line')
    expect(statusText(transcription('QUEUED', { queued: 3 }))).toBe('3rd in line')
    expect(statusText(transcription('QUEUED'))).toBe('Waiting its turn')
    expect(statusText(transcription('RECOGNIZING', { percent: 40 }))).toBe('Being heard · 40%')
    expect(statusText(transcription('RECOGNIZING'))).toBe('Being heard')
    expect(statusText(transcription('CANCELLED'))).toBe('Cancelled')
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd'])
  })

  it('are on while queued, starting or heard', () => {
    expect(['QUEUED', 'STARTING', 'RECOGNIZING'].every((s) => isOn({ status: s as Transcription['status'] }))).toBe(true)
    expect(['DONE', 'FAILED', 'CANCELLED'].some((s) => isOn({ status: s as Transcription['status'] }))).toBe(false)
  })

  it('list those on first in line first, and those that ended the latest first', () => {
    const { on, ended } = split([
      transcription('DONE', { createdAt: '2026-10-28T09:00:00Z', finishedAt: '2026-10-28T09:30:00Z' }),
      transcription('QUEUED', { queued: 2, createdAt: '2026-10-28T10:01:00Z' }),
      transcription('RECOGNIZING', { createdAt: '2026-10-28T10:05:00Z' }),
      transcription('QUEUED', { queued: 1, createdAt: '2026-10-28T10:02:00Z' }),
      transcription('FAILED', { createdAt: '2026-10-28T09:40:00Z', finishedAt: '2026-10-28T09:45:00Z' }),
    ])
    expect(on.map((t) => [t.status, t.queued])).toEqual([['RECOGNIZING', null], ['QUEUED', 1], ['QUEUED', 2]])
    expect(ended.map((t) => t.status)).toEqual(['FAILED', 'DONE'])
  })

  it('say how long they took, or have taken so far', () => {
    expect(took(transcription('DONE', { finishedAt: '2026-10-28T10:24:30Z' }))).toBe('24:30')
    expect(took(transcription('RECOGNIZING'), new Date('2026-10-28T11:02:05Z'))).toBe('1:02:05')
    // In Ljubljana, whatever the browser's time zone
    expect(formatWhen('2026-10-28T13:02:00Z')).toBe('28 Oct, 14:02')
  })

  it("say what a transcript has, and who speaks in it", () => {
    expect(transcriptSummary({ id: 't', segments: 187, words: 7585, duration: 3314, speakers: [] })).toBe('187 lines · 7,585 words · 55:14')
    expect(transcriptSummary({ id: 't', segments: 1, words: 1, duration: null, speakers: [{ label: '1', name: null }] }))
      .toBe('1 line · 1 word · 1 speaker')
    expect(speakerName('2', [{ label: '1', name: 'Oto Brglez' }])).toBe('Speaker 2')
    expect(speakerName('1', [{ label: '1', name: 'Oto Brglez' }])).toBe('Oto Brglez')
    expect(speakerName(null, [])).toBe(null)
    expect(timestamp(3725.9)).toBe('1:02:05')
    expect(providerName('soniox')).toBe('Soniox')
    expect(providerName('google')).toBe('Google Speech-to-Text')
  })

  it("choose an episode's full audio, else its full video, and know what's transcribed already", () => {
    const episode = {
      media: [{ id: 'v', name: 'S05E07.mp4', kind: 'FULL_VIDEO' }, { id: 'a', name: 'S05E07.mp3', kind: 'FULL_AUDIO' }],
      transcripts: [{ mediaId: 'a', provider: 'google' }],
    }
    expect(defaultFile(episode)?.id).toBe('a')
    expect(defaultFile({ media: [{ id: 'b', name: 'bonus.mp3', kind: 'BONUS_AUDIO' }] })).toBe(null)
    expect(hasTranscript(episode, 'a', 'google')).toBe(true)
    expect(hasTranscript(episode, 'a', 'soniox')).toBe(false)
  })

  it("are in Slovenian, but a show's in English, e.g. Borderless's, in English", () => {
    expect(languageOf({ language: 'sl-SI' })).toBe('sl-SI')
    expect(languageOf({ language: 'en-GB' })).toBe('en-GB')
    expect(languageOf({ language: 'en-US' })).toBe('en-GB')
    expect(languageOf({ language: 'hr-HR' })).toBe('sl-SI')
  })
})
