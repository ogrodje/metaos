import { describe, expect, it, vi } from 'vitest'
import {
  codecName, formatDuration, formatSize, infoLine, isMedia, type MediaInfo, type MediaKind, mediaConcerns, mediaParts, namedBonus, type Picked, placeAll,
  type Probe, probeOf, refusal, typeOf, warnings,
} from '@/media'
import { errorMessage } from '@/graphql'
import { nextOffset, sendInChunks } from '@/resumable'
import { t, tSl } from './i18n'

// graphql.ts's UserError, without Nuxt
vi.mock('#imports', () => ({}))

let ids = 0

const VIDEO: Probe = {
  video: true, audio: true, duration: 3600, width: 1920, height: 1080, frameRate: 25, videoFormat: 'AVC', audioFormat: 'AAC',
  sampleRate: 48000, channels: 2,
}
const AUDIO: Probe = {
  video: false, audio: true, duration: 3600, width: null, height: null, frameRate: null, videoFormat: null, audioFormat: 'MPEG Audio',
  sampleRate: 44100, channels: 2,
}

function picked(name: string, probe: Probe | null, size = 1000): Picked {
  // A file that says it's big without being so
  const file = new File([], name)
  Object.defineProperty(file, 'size', { value: size })
  return { id: ++ids, file, name, type: typeOf(name, probe), probe }
}

const info = (change: Partial<MediaInfo> = {}): MediaInfo => ({
  duration: 5525, bitrate: 8_000_000, container: 'MP4', videoCodec: 'h264', videoProfile: 'High', width: 1920, height: 1080,
  frameRate: 25, bitDepth: 8, color: 'bt709', audioCodec: 'aac', sampleRate: 48000, channels: 2, audioBitrate: 192_000,
  audioTracks: 1, madeAt: null, encoder: null, ...change,
})

describe('probeOf', () => {
  it("reads MediaInfo's tracks, leaving out a cover that's a picture", () => {
    const probe = probeOf({
      media: {
        '@ref': '',
        track: [
          { '@type': 'General', Duration: 5525.04, Format: 'MPEG-4' },
          { '@type': 'Video', Format: 'AVC', Width: 1920, Height: 1080, FrameRate: 29.97, Duration: 5525 },
          { '@type': 'Audio', Format: 'AAC', SamplingRate: 48000, Channels: 2 },
        ],
      },
    } as never)
    expect(probe).toEqual({
      video: true, audio: true, duration: 5525.04, width: 1920, height: 1080, frameRate: 29.97, videoFormat: 'AVC', audioFormat: 'AAC',
      sampleRate: 48000, channels: 2,
    })

    const m4a = probeOf({
      media: {
        '@ref': '',
        track: [
          { '@type': 'General', Duration: 60 },
          { '@type': 'Audio', Format: 'AAC', SamplingRate: 44100, Channels: 2 },
          { '@type': 'Video', Format: 'JPEG', Width: 3000, Height: 3000 },
        ],
      },
    } as never)
    expect(m4a.video).toBe(false)
    expect(m4a.width).toBeNull()
  })

  it("says nothing of what isn't there", () => {
    expect(probeOf({ media: undefined })).toMatchObject({ video: false, audio: false, duration: null })
  })
})

describe('typeOf and refusal', () => {
  it('sends a file as its extension says, but an MP4 without video as audio', () => {
    expect(typeOf('S05E08.MOV', VIDEO)).toBe('video/quicktime')
    expect(typeOf('S05E08.mp4', AUDIO)).toBe('audio/mp4')
    expect(typeOf('S05E08.mp4', null)).toBe('video/mp4')
    expect(typeOf('S05E08.flac', AUDIO)).toBe('audio/flac')
    expect(typeOf('S05E08.mkv', VIDEO)).toBeNull()
  })

  it('tells video and audio from the rest of a folder, also what MetaOS refuses', () => {
    expect(['S05E08.mov', 'S05E08.MKV', 'S05E08.opus', 'notes.txt', '.DS_Store', 'cover.png'].filter((name) => isMedia({ name })))
      .toEqual(['S05E08.mov', 'S05E08.MKV', 'S05E08.opus'])
  })

  it("refuses what MetaOS doesn't take", () => {
    expect(refusal(picked('S05E08.mkv', VIDEO), t, 'en-GB')).toBe("It isn't MP4 or MOV, or MP3, M4A, WAV or FLAC.")
    expect(refusal(picked('S05E08.mp4', VIDEO, 64_400_000_000), t, 'en-GB')).toBe("It's 64 GB; a file is up to 50 GB.")
    expect(refusal(picked('S05E08.mp3', VIDEO), t, 'en-GB')).toBe('It has video, but its name says audio.')
    expect(refusal(picked('S05E08.mov', AUDIO), t, 'en-GB')).toBe("It's a MOV without video; audio is MP3, M4A, WAV or FLAC.")
    expect(refusal(picked('S05E08.mp4', { ...VIDEO, video: false, audio: false }), t, 'en-GB')).toBe("There's neither video nor audio in it.")
    expect(refusal(picked('S05E08.mp4', VIDEO), t, 'en-GB')).toBeNull()
    // MetaOS reads what the browser couldn't
    expect(refusal(picked('S05E08.wav', null), t, 'en-GB')).toBeNull()
  })
})

describe('placeAll', () => {
  it('places the longest video as the full video and the longest audio as the full audio, the rest as bonuses, and a bonus by its name', () => {
    const placement = placeAll([
      picked('S05E08-bonus.mp4', { ...VIDEO, duration: 7200 }), picked('S05E08.mp4', VIDEO), picked('S05E08.mp3', AUDIO),
      picked('S05E08-extra.wav', { ...AUDIO, duration: 300 }), picked('notes.mkv', VIDEO),
    ], t, 'en-GB')
    expect(Object.fromEntries(placement.placed.map((it) => [it.picked.name, it.kind]))).toEqual({
      'S05E08-bonus.mp4': 'BONUS_VIDEO', 'S05E08.mp4': 'FULL_VIDEO', 'S05E08.mp3': 'FULL_AUDIO', 'S05E08-extra.wav': 'BONUS_AUDIO',
    })
    expect(placement.refused.map((it) => it.picked.name)).toEqual(['notes.mkv'])
  })

  it("doesn't take the full ones' places from those picked before", () => {
    const placement = placeAll([picked('S05E08 final.mov', VIDEO)], t, 'en-GB', ['FULL_VIDEO'])
    expect(placement.placed.map((it) => it.kind)).toEqual(['BONUS_VIDEO'])
  })

  it('tells a bonus by its name', () => {
    expect(namedBonus('S05E08-bonus.mp4')).toBe(true)
    expect(namedBonus('Bonus 2.mov')).toBe(true)
    expect(namedBonus('S05E08_BONUS_audio.mp3')).toBe(true)
    expect(namedBonus('S05E08.mp4')).toBe(false)
    expect(namedBonus('S05E08.bonus')).toBe(false)
  })
})

describe('warnings', () => {
  it('says what looks wrong for what it is to be', () => {
    expect(warnings({ ...VIDEO, width: 1280, height: 720 }, 'FULL_VIDEO', t, 'en-GB')).toEqual(["Under 1080p: it's 1280 × 720"])
    expect(warnings({ ...VIDEO, width: 1280, height: 720 }, 'BONUS_VIDEO', t, 'en-GB')).toEqual([])
    expect(warnings({ ...AUDIO, sampleRate: 32000 }, 'FULL_AUDIO', t, 'en-GB')).toEqual(['Its audio is 32 kHz, not 44.1 or 48 kHz'])
    expect(warnings({ ...VIDEO, audio: false, sampleRate: null }, 'FULL_VIDEO', t, 'en-GB')).toEqual(['It has no audio'])
    expect(warnings(null, 'FULL_VIDEO', t, 'en-GB')).toEqual([])
  })
})

describe('formatting', () => {
  it('says lengths, sizes and codecs as people know them', () => {
    expect(formatDuration(5525.4)).toBe('1:32:05')
    expect(formatDuration(245)).toBe('4:05')
    expect(formatSize(4_200_000_000, 'en-GB')).toBe('4.2 GB')
    expect(formatSize(820_000_000, 'en-GB')).toBe('820 MB')
    expect(formatSize(512, 'en-GB')).toBe('512 bytes')
    expect(codecName('h264')).toBe('H.264')
    expect(codecName('pcm_s24le')).toBe('PCM 24-bit')
    expect(codecName('dvvideo')).toBe('DVVIDEO')
  })

  it("says what's in a file on one line", () => {
    expect(infoLine(info(), 4_200_000_000, 'en-GB')).toBe('1:32:05 · 1920 × 1080 · 25 fps · H.264 · AAC 48 kHz · 4.2 GB')
    expect(infoLine(info({ frameRate: 30000 / 1001 }), 1, 'en-GB')).toContain('29.97 fps')
    expect(infoLine(info({ videoCodec: null, width: null, height: null, frameRate: null, audioCodec: 'mp3', sampleRate: 44100 }), 86_000_000, 'en-GB'))
      .toBe('1:32:05 · MP3 44.1 kHz · 86 MB')
    expect(infoLine(info({ frameRate: 30000 / 1001, audioCodec: 'mp3', sampleRate: 44100 }), 4_200_000_000, 'sl-SI'))
      .toBe('1:32:05 · 1920 × 1080 · 29,97 fps · H.264 · MP3 44,1 kHz · 4,2 GB')
  })
})

describe('mediaConcerns', () => {
  it('says when the full audio is not as long as the full video', () => {
    expect(mediaConcerns([{ kind: 'FULL_VIDEO', info: info() }, { kind: 'FULL_AUDIO', info: info({ duration: 5450 }) }], t))
      .toEqual(['The full audio is 1:15 shorter than the full video'])
    expect(mediaConcerns([{ kind: 'FULL_VIDEO', info: info() }, { kind: 'FULL_AUDIO', info: info({ duration: 5528 }) }], t)).toEqual([])
    expect(mediaConcerns([{ kind: 'FULL_VIDEO', info: info() }, { kind: 'BONUS_AUDIO', info: info({ duration: 60 }) }], t)).toEqual([])
  })
})

describe('sendInChunks', () => {
  const controller = () => new AbortController()

  it('goes on from what Cloud Storage says it has', () => {
    expect(nextOffset('bytes=0-262143')).toBe(262144)
    expect(nextOffset(null)).toBe(0)
    expect(nextOffset('bytes=5-9')).toBe(0)
  })

  it('sends a file in chunks, and after a break asks how much it has and goes on from there', async () => {
    const file = new Blob([new Uint8Array(10)])
    const sent: string[] = []
    let failed = false
    const send = async (range: string) => {
      sent.push(range)
      if (range === 'bytes 4-7/10' && !failed) {
        failed = true
        throw new Error('The connection broke')
      }
      if (range === 'bytes */10') return { status: 308, range: 'bytes=0-3' }
      if (range === 'bytes 8-9/10') return { status: 201, range: null }
      return { status: 308, range: `bytes=0-${Number(/-(\d+)\//.exec(range)![1])}` }
    }
    const progress: number[] = []
    await sendInChunks('session', file, (bytes) => progress.push(bytes), controller().signal, 4, send, async () => {})

    expect(sent).toEqual(['bytes 0-3/10', 'bytes 4-7/10', 'bytes */10', 'bytes 4-7/10', 'bytes 8-9/10'])
    expect(progress.at(-1)).toBe(10)
  })

  it('gives up on an upload that expired, or when the connection keeps breaking', async () => {
    const file = new Blob([new Uint8Array(10)])
    const failure = (status: number) =>
      sendInChunks('session', file, () => {}, controller().signal, 4, async () => ({ status, range: null }), async () => {})
        .then(() => 'sent', (error) => errorMessage(error, t))
    expect(await failure(410)).toBe('The upload expired; send the file again')
    expect(await failure(503)).toBe('The connection kept breaking while it was being sent')
    expect(await failure(403)).toBe('Google Cloud Storage answered 403')
  })
})

describe('mediaParts', () => {
  const file = (kind: MediaKind, duration: number | null) => ({ kind, info: { duration } as MediaInfo })

  it("says what's there, then what's missing once it's recorded", () => {
    expect(mediaParts([file('FULL_AUDIO', 3314), file('BONUS_AUDIO', 772)], true, t)).toEqual([
      { text: 'Full audio, 55:14' }, { text: 'a bonus, 12:52' }, { text: 'no video', missing: true },
    ])
    expect(mediaParts([file('BONUS_VIDEO', null), file('BONUS_AUDIO', null)], false, t)).toEqual([{ text: '2 bonuses' }])
    expect(mediaParts([], false, t)).toEqual([])
    expect(mediaParts([file('BONUS_VIDEO', null), file('BONUS_AUDIO', null), file('BONUS_AUDIO', null)], false, tSl))
      .toEqual([{ text: '3 bonus posnetki' }])
  })
})
