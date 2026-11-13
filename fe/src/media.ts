// An episode's video and audio (MediaFiles.kt): its full video and full audio, one each, and its bonuses, any number,
// each as video or audio only. Uploaded from its page (MediaUpload.vue): the browser reads what's in each file before
// it's sent (mediainfo.js), places it by that and its name and says what looks wrong; MetaOS reads it again with
// ffprobe once it's there, which is what's kept.

import type { MediaInfoResult } from 'mediainfo.js'
import type { LinePart } from '@/episodes'
import type { DateLocale, Translate } from '@/i18n'

export type MediaKind = 'FULL_VIDEO' | 'FULL_AUDIO' | 'BONUS_VIDEO' | 'BONUS_AUDIO'

/** What's in a file, as ffprobe read it (MediaInfo in the schema); null for what the file doesn't say */
export interface MediaInfo {
  // Seconds; bits a second
  duration: number | null
  bitrate: number | null
  container: string | null
  videoCodec: string | null
  videoProfile: string | null
  width: number | null
  height: number | null
  frameRate: number | null
  bitDepth: number | null
  color: string | null
  audioCodec: string | null
  sampleRate: number | null
  channels: number | null
  audioBitrate: number | null
  audioTracks: number
  madeAt: string | null
  encoder: string | null
}

/** One of an episode's video or audio files */
export interface MediaFile {
  id: string
  kind: MediaKind
  title: string | null
  name: string
  contentType: string
  // Bytes
  size: number
  md5: string | null
  uploadedBy: string
  uploadedAt: string
  info: MediaInfo
}

const INFO_FIELDS = 'duration bitrate container videoCodec videoProfile width height frameRate bitDepth color audioCodec ' +
  'sampleRate channels audioBitrate audioTracks madeAt encoder'
export const MEDIA_FILE_FIELDS = `id kind title name contentType size md5 uploadedBy uploadedAt info { ${INFO_FIELDS} }`
export const MEDIA_FIELDS = `media { ${MEDIA_FILE_FIELDS} }`

export interface Kind {
  kind: MediaKind
  video: boolean
  // One per episode; else any number
  full: boolean
}

/** The kinds, as the episode's page and the dialog list them */
export const KINDS: Kind[] = [
  { kind: 'FULL_VIDEO', video: true, full: true },
  { kind: 'FULL_AUDIO', video: false, full: true },
  { kind: 'BONUS_VIDEO', video: true, full: false },
  { kind: 'BONUS_AUDIO', video: false, full: false },
]

export const kindOf = (kind: MediaKind) => KINDS.find((it) => it.kind === kind)!

/** A kind's name, as a label, e.g. Full video */
export const kindLabel = (kind: MediaKind, t: Translate) => t(`media.kind.${kind}`)

/** A kind's name within a sentence, e.g. Upload the full video */
export const kindName = (kind: MediaKind, t: Translate) => t(`media.kindName.${kind}`)

/** The other kind of the same medium: a full video's is the bonus video */
export const otherKind = (kind: MediaKind): MediaKind => KINDS.find((it) => it.video === kindOf(kind).video && it.kind !== kind)!.kind

// What MetaOS takes, by the file's extension, as browsers say different types for the same files (MEDIA_TYPES)
const TYPES: Record<string, { type: string, video: boolean }> = {
  mp4: { type: 'video/mp4', video: true },
  m4v: { type: 'video/mp4', video: true },
  mov: { type: 'video/quicktime', video: true },
  mp3: { type: 'audio/mpeg', video: false },
  m4a: { type: 'audio/mp4', video: false },
  wav: { type: 'audio/wav', video: false },
  flac: { type: 'audio/flac', video: false },
}

/** For a file input: what may be chosen */
export const ACCEPT = Object.keys(TYPES).map((extension) => `.${extension}`).join(',')

// As MetaOS takes them (MAX_MEDIA_BYTES)
export const MAX_MEDIA_BYTES = 50_000_000_000

const extensionOf = (name: string) => /\.([^.]+)$/.exec(name)?.[1]?.toLowerCase() ?? ''

// Video and audio, also what MetaOS doesn't take, which is refused rather than left out, so it says why
const MEDIA = /\.(mp4|m4v|mov|mp3|m4a|wav|flac|mkv|webm|avi|mts|m2ts|mxf|wmv|aac|ogg|oga|opus|aiff?|wma)$/i

/** Whether a file is video or audio at all, by its name: anything else in a folder is left out */
export const isMedia = (file: Pick<File, 'name'>) => MEDIA.test(file.name)

/** What the browser read of a file: whether it has video and audio, and what they are; null for what it doesn't say */
export interface Probe {
  video: boolean
  audio: boolean
  duration: number | null
  width: number | null
  height: number | null
  frameRate: number | null
  videoFormat: string | null
  audioFormat: string | null
  sampleRate: number | null
  channels: number | null
}

/**
 * What MediaInfo says of a file, as a [Probe]. A picture without a frame rate in the file, e.g. an M4A's cover, isn't
 * its video.
 */
export function probeOf(result: Pick<MediaInfoResult, 'media'>): Probe {
  // Read as they come, as MediaInfo's fields differ from file to file
  const tracks = (result.media?.track ?? []) as unknown as Record<string, unknown>[]
  const number = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : null)
  const text = (value: unknown) => (typeof value === 'string' && value ? value : null)
  const general = tracks.find((track) => track['@type'] === 'General')
  const video = tracks.find((track) => track['@type'] === 'Video' && (number(track.FrameRate) || number(track.Duration)))
  const audio = tracks.find((track) => track['@type'] === 'Audio')
  return {
    video: !!video,
    audio: !!audio,
    duration: number(general?.Duration) ?? number(video?.Duration) ?? number(audio?.Duration),
    width: number(video?.Width),
    height: number(video?.Height),
    frameRate: number(video?.FrameRate),
    videoFormat: text(video?.Format),
    audioFormat: text(audio?.Format),
    sampleRate: number(audio?.SamplingRate),
    channels: number(audio?.Channels),
  }
}

/** A file the user picked, as the browser read it: its type, from its name and what's in it, and what's in it */
export interface Picked {
  id: number
  file: File
  name: string
  // What it's sent as; null when MetaOS doesn't take it
  type: string | null
  probe: Probe | null
}

/**
 * The content type a file is sent as: by its extension, but an MP4 without video is audio (M4A in all but name), and
 * null when MetaOS doesn't take it
 */
export function typeOf(name: string, probe: Probe | null): string | null {
  const known = TYPES[extensionOf(name)]
  if (!known) return null
  if (probe && !probe.video && known.type === 'video/mp4') return 'audio/mp4'
  return known.type
}

/** Why a file can't be any of the episode's video or audio, or null when it can */
export function refusal(picked: Picked, t: Translate, dateLocale: DateLocale): string | null {
  if (!TYPES[extensionOf(picked.name)]) return t('media.refusal.format')
  if (picked.file.size > MAX_MEDIA_BYTES) {
    return t('media.refusal.size', { size: formatSize(picked.file.size, dateLocale), max: formatSize(MAX_MEDIA_BYTES, dateLocale) })
  }
  if (!picked.probe) return null
  if (!picked.probe.video && !picked.probe.audio) return t('media.refusal.empty')
  const video = TYPES[extensionOf(picked.name)]!.video
  if (picked.probe.video && !video) return t('media.refusal.videoAsAudio')
  if (!picked.probe.video && picked.type === 'video/quicktime') return t('media.refusal.movWithoutVideo')
  return null
}

/** Whether a picked file is video: by what's in it, or else its name */
export function isVideo(picked: Picked) {
  return picked.probe ? picked.probe.video : (TYPES[extensionOf(picked.name)]?.video ?? false)
}

/** Whether its name says it's a bonus, e.g. S05E08-bonus.mp4 or Bonus 2.mov */
export const namedBonus = (name: string) => /(^|[^a-z])bonus/i.test(name.replace(/\.[^.]*$/, ''))

export interface Placement {
  placed: { picked: Picked, kind: MediaKind }[]
  refused: { picked: Picked, reason: string }[]
}

/**
 * Where files dropped together go, by name: the longest video the full video and the longest audio the full audio, as
 * bonuses are shorter, unless its name says it's a bonus or another one picked has that place already ([taken]); the
 * rest are bonuses.
 */
export function placeAll(files: Picked[], t: Translate, dateLocale: DateLocale, taken: MediaKind[] = []): Placement {
  const placement: Placement = { placed: [], refused: [] }
  const fitting: Picked[] = []
  for (const picked of [...files].sort((a, b) => a.name.localeCompare(b.name))) {
    const reason = refusal(picked, t, dateLocale)
    if (reason) placement.refused.push({ picked, reason })
    else fitting.push(picked)
  }
  const length = (picked: Picked) => picked.probe?.duration ?? 0
  const full = (video: boolean): MediaKind => (video ? 'FULL_VIDEO' : 'FULL_AUDIO')
  const fulls = new Map<MediaKind, Picked>()
  for (const video of [true, false]) {
    if (taken.includes(full(video))) continue
    const longest = fitting.filter((picked) => isVideo(picked) === video && !namedBonus(picked.name))
      .reduce<Picked | undefined>((best, picked) => (!best || length(picked) > length(best) ? picked : best), undefined)
    if (longest) fulls.set(full(video), longest)
  }
  for (const picked of fitting) {
    const kind = full(isVideo(picked))
    placement.placed.push({ picked, kind: fulls.get(kind) === picked ? kind : otherKind(kind) })
  }
  return placement
}

// Podcast apps take 44.1 and 48 kHz
const SAMPLE_RATES = [44100, 48000]

/** What looks wrong with a file as the [kind] it's to be, before it's sent: MetaOS takes it all the same */
export function warnings(probe: Probe | null, kind: MediaKind, t: Translate, dateLocale: DateLocale): string[] {
  if (!probe) return []
  const found: string[] = []
  if (kind === 'FULL_VIDEO' && probe.height && probe.height < 1080) {
    found.push(t('media.warning.under1080p', { width: probe.width, height: probe.height }))
  }
  if (probe.sampleRate && !SAMPLE_RATES.includes(probe.sampleRate)) {
    found.push(t('media.warning.sampleRate', { rate: formatRate(probe.sampleRate, dateLocale) }))
  }
  if (kindOf(kind).video && !probe.audio) found.push(t('media.warning.noAudio'))
  return found
}

/** A length, e.g. 1:32:05 or 4:05 */
export function formatDuration(seconds: number): string {
  const total = Math.round(seconds)
  const [hours, minutes, rest] = [Math.floor(total / 3600), Math.floor((total % 3600) / 60), total % 60]
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours ? `${hours}:${pad(minutes)}:${pad(rest)}` : `${minutes}:${pad(rest)}`
}

/** A size as the Finder says it, in the page's language, e.g. 4.2 GB or 820 MB (4,2 GB in Slovenian) */
export function formatSize(bytes: number, dateLocale: DateLocale): string {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte']
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit++
  }
  const digits = unit && value < 10 ? 1 : 0
  return new Intl.NumberFormat(dateLocale, {
    style: 'unit', unit: units[unit], unitDisplay: unit ? 'short' : 'long', minimumFractionDigits: digits, maximumFractionDigits: digits,
  }).format(unit ? value : bytes)
}

// A number with at most [digits] decimals, in the page's language, e.g. 29.97 (29,97 in Slovenian)
const formatNumber = (value: number, digits: number, dateLocale: DateLocale) =>
  new Intl.NumberFormat(dateLocale, { maximumFractionDigits: digits }).format(value)

const formatRate = (hertz: number, dateLocale: DateLocale) => `${formatNumber(hertz / 1000, 1, dateLocale)} kHz`

/** A frame rate, e.g. 29.97 fps */
export const formatFrameRate = (rate: number, dateLocale: DateLocale) => `${formatNumber(rate, 2, dateLocale)} fps`

/** A sample rate, e.g. 44.1 kHz */
export const formatSampleRate = formatRate

// ffprobe's names for codecs, as people know them
const CODECS: Record<string, string> = {
  h264: 'H.264', hevc: 'HEVC', prores: 'ProRes', av1: 'AV1', vp9: 'VP9', mpeg4: 'MPEG-4', dnxhd: 'DNxHD',
  aac: 'AAC', mp3: 'MP3', flac: 'FLAC', opus: 'Opus', alac: 'ALAC', vorbis: 'Vorbis', ac3: 'AC-3',
}

/** A codec as people know it: H.264, AAC, PCM 24-bit */
export function codecName(codec: string): string {
  const pcm = /^pcm_[su](\d+)/.exec(codec)
  if (pcm) return `PCM ${pcm[1]}-bit`
  return CODECS[codec] ?? codec.toUpperCase()
}

/** What's in a file, on one line: 1:32:05 · 1920 × 1080 · 25 fps · H.264 · AAC 48 kHz · 4.2 GB */
export function infoLine(info: MediaInfo, size: number, dateLocale: DateLocale): string {
  const parts: string[] = []
  if (info.duration) parts.push(formatDuration(info.duration))
  if (info.width && info.height) parts.push(`${info.width} × ${info.height}`)
  if (info.frameRate) parts.push(formatFrameRate(info.frameRate, dateLocale))
  if (info.videoCodec) parts.push(codecName(info.videoCodec))
  if (info.audioCodec) {
    parts.push([codecName(info.audioCodec), info.sampleRate && formatRate(info.sampleRate, dateLocale)].filter(Boolean).join(' '))
  }
  parts.push(formatSize(size, dateLocale))
  return parts.join(' · ')
}

// How far apart the full video and full audio may be in length before it's said, as their ends may be trimmed apart
const LENGTHS_APART = 5

/** What needs attention of an episode's video and audio, e.g. on its page: a full audio that's not as long as the video */
export function mediaConcerns(files: Pick<MediaFile, 'kind' | 'info'>[], t: Translate): string[] {
  const video = files.find((file) => file.kind === 'FULL_VIDEO')?.info.duration
  const audio = files.find((file) => file.kind === 'FULL_AUDIO')?.info.duration
  if (!video || !audio || Math.abs(video - audio) <= LENGTHS_APART) return []
  const length = formatDuration(Math.abs(video - audio))
  return [t(audio < video ? 'media.concerns.audioShorter' : 'media.concerns.audioLonger', { length })]
}

/**
 * What an episode's video and audio are, in a line, e.g. Full audio, 55:14 · a bonus, 12:52 · no video: what's there,
 * then, once it's [recorded], what's missing
 */
export function mediaParts(files: Pick<MediaFile, 'kind' | 'info'>[], recorded: boolean, t: Translate): LinePart[] {
  const long = (what: string, file: Pick<MediaFile, 'info'>) =>
    (file.info.duration ? t('media.parts.withLength', { what, length: formatDuration(file.info.duration) }) : what)
  const video = files.find((file) => file.kind === 'FULL_VIDEO')
  const audio = files.find((file) => file.kind === 'FULL_AUDIO')
  const bonuses = files.filter((file) => file.kind === 'BONUS_VIDEO' || file.kind === 'BONUS_AUDIO')
  const n = bonuses.length
  return [
    ...(video ? [{ text: long(t('media.parts.fullVideo'), video) }] : []),
    ...(audio ? [{ text: long(t('media.parts.fullAudio'), audio) }] : []),
    ...(n === 1 ? [{ text: long(t('media.parts.bonus'), bonuses[0]!) }] : n ? [{ text: t('media.parts.bonuses', { n }, n) }] : []),
    ...(!video && recorded ? [{ text: t('media.parts.noVideo'), missing: true }] : []),
    ...(!audio && recorded ? [{ text: t('media.parts.noAudio'), missing: true }] : []),
  ]
}
