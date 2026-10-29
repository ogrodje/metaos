import { DATE_LOCALE, TIME_ZONE } from '@/episodes'
import { formatDuration } from '@/media'

// Episodes' transcriptions and the transcripts they make, as the Transcriptions page shows them: MetaOS's GraphQL
// transcriptions, transcript and speechProviders, and transcribeEpisode, cancelTranscription and removeTranscript, as
// transcribe_episode, list_transcriptions, cancel_transcription and remove_transcript have them over MCP

/** How far a transcription is: waiting for its turn at its provider, starting, being heard; then done, failed or cancelled */
export type TranscriptionStatus = 'QUEUED' | 'STARTING' | 'RECOGNIZING' | 'DONE' | 'FAILED' | 'CANCELLED'

export interface TranscriptSpeaker {
  label: string
  name: string | null
}

/** A transcript, without what it says: how much there is, and who speaks */
export interface TranscriptSummary {
  id: string
  segments: number
  words: number
  duration: number | null
  speakers: TranscriptSpeaker[]
}

export interface Transcription {
  id: string
  // Null once the episode is deleted
  episode: { id: string, code: string | null, title: string } | null
  mediaId: string
  mediaName: string
  provider: string
  model: string | null
  language: string
  batch: boolean
  status: TranscriptionStatus
  // While it's QUEUED, its place in line, 1 next
  queued: number | null
  // While it's RECOGNIZING, how far the provider got, by tens, where it says
  percent: number | null
  error: string | null
  // The transcript it made, while it's there
  transcript: TranscriptSummary | null
  requestedBy: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

/** A stretch of what was said, from start to end, in seconds from the start of the file */
export interface TranscriptLine {
  speaker: string | null
  start: number
  end: number
  text: string
}

/** A provider MetaOS has, and how many files it hears at once */
export interface SpeechProvider {
  name: string
  model: string
  slots: number
}

/** An episode's files, for choosing which to transcribe, and who has transcribed them already */
export interface EpisodeFiles {
  id: string
  code: string | null
  title: string
  // What it's spoken in, BCP-47: its show's language, else Slovenian
  language: string
  media: { id: string, name: string, kind: string }[]
  transcripts: { mediaId: string, provider: string }[]
}

export const TRANSCRIPTION_FIELDS = 'id mediaId mediaName provider model language batch status queued percent error requestedBy '
  + 'createdAt startedAt finishedAt episode { id code title } transcript { id segments words duration speakers { label name } }'

/** The latest transcriptions, and the providers to transcribe with */
export const TRANSCRIPTIONS = `{ transcriptions(limit: 200) { ${TRANSCRIPTION_FIELDS} } speechProviders { name model slots } }`

/** What a transcript says */
export const TRANSCRIPT_LINES = 'query ($id: ID!) { transcript(id: $id) { id lines { speaker start end text } speakers { label name } } }'

/** An episode's files, by its code or ID */
export const EPISODE_FILES = 'query ($ref: String!) { episode(ref: $ref) { id code title language media { id name kind } transcripts { mediaId provider } } }'

export const TRANSCRIBE = 'mutation ($ref: String!, $media: ID, $provider: String, $language: String, $batch: Boolean!, $replace: Boolean!) '
  + '{ transcription: transcribeEpisode(ref: $ref, media: $media, provider: $provider, language: $language, batch: $batch, '
  + `replace: $replace) { ${TRANSCRIPTION_FIELDS} } }`

export const CANCEL_TRANSCRIPTION = `mutation ($id: ID!) { transcription: cancelTranscription(id: $id) { ${TRANSCRIPTION_FIELDS} } }`

export const REMOVE_TRANSCRIPT = 'mutation ($id: ID!) { removeTranscript(id: $id) }'

const ON: TranscriptionStatus[] = ['QUEUED', 'STARTING', 'RECOGNIZING']

/** Whether it's still on: waiting for its turn, starting, or being heard */
export function isOn(transcription: Pick<Transcription, 'status'>) {
  return ON.includes(transcription.status)
}

const ordinals = new Intl.PluralRules('en-GB', { type: 'ordinal' })
const SUFFIXES: Record<string, string> = { one: 'st', two: 'nd', few: 'rd', other: 'th' }

/** 1st, 2nd, 3rd, 4th, ... */
export function ordinal(n: number) {
  return `${n}${SUFFIXES[ordinals.select(n)]}`
}

/** How far it is, as people say it, e.g. Next in line, 3rd in line, Starting, Being heard · 40%, Done */
export function statusText(transcription: Pick<Transcription, 'status' | 'queued' | 'percent'>) {
  switch (transcription.status) {
    case 'QUEUED':
      if (transcription.queued === 1) return 'Next in line'
      return transcription.queued ? `${ordinal(transcription.queued)} in line` : 'Waiting its turn'
    case 'STARTING':
      return 'Starting'
    case 'RECOGNIZING':
      return transcription.percent != null ? `Being heard · ${transcription.percent}%` : 'Being heard'
    case 'DONE':
      return 'Done'
    case 'FAILED':
      return 'Failed'
    case 'CANCELLED':
      return 'Cancelled'
  }
}

const PROVIDERS: Record<string, string> = { google: 'Google Speech-to-Text', soniox: 'Soniox' }

/** A provider as people know it, e.g. Soniox */
export function providerName(provider: string) {
  return PROVIDERS[provider] ?? provider
}

/** How long it took, from when it was asked for till it ended, or so far, e.g. 4:05; null while it isn't started */
export function took(transcription: Pick<Transcription, 'createdAt' | 'finishedAt'>, now = new Date()) {
  const from = new Date(transcription.createdAt).getTime()
  const to = transcription.finishedAt ? new Date(transcription.finishedAt).getTime() : now.getTime()
  return to >= from ? formatDuration((to - from) / 1000) : null
}

/** What a transcript has, e.g. 187 lines · 7,585 words · 55:14 · 2 speakers */
export function transcriptSummary(transcript: TranscriptSummary) {
  return [
    `${transcript.segments.toLocaleString('en-GB')} ${transcript.segments === 1 ? 'line' : 'lines'}`,
    `${transcript.words.toLocaleString('en-GB')} ${transcript.words === 1 ? 'word' : 'words'}`,
    transcript.duration != null ? formatDuration(transcript.duration) : null,
    transcript.speakers.length ? `${transcript.speakers.length} ${transcript.speakers.length === 1 ? 'speaker' : 'speakers'}` : null,
  ].filter(Boolean).join(' · ')
}

/** Those on, first in line first, and those that ended, the latest first */
export function split<T extends Pick<Transcription, 'status' | 'queued' | 'createdAt' | 'finishedAt'>>(transcriptions: T[]) {
  const rank = (t: T) => (t.status === 'QUEUED' ? 1 : 0)
  const on = transcriptions.filter(isOn).sort((a, b) =>
    rank(a) - rank(b) || (a.queued ?? Infinity) - (b.queued ?? Infinity) || a.createdAt.localeCompare(b.createdAt))
  const ended = transcriptions.filter((t) => !isOn(t))
    .sort((a, b) => (b.finishedAt ?? b.createdAt).localeCompare(a.finishedAt ?? a.createdAt))
  return { on, ended }
}

/** Where a line starts, as h:mm:ss, as YouTube's chapters have it */
export function timestamp(seconds: number) {
  const total = Math.floor(seconds)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${Math.floor(total / 3600)}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}

/** Who said a line: their name once they're named, else Speaker 1, ...; null where the provider doesn't tell */
export function speakerName(label: string | null, speakers: TranscriptSpeaker[]) {
  if (label == null) return null
  return speakers.find((s) => s.label === label)?.name ?? `Speaker ${label}`
}

/** The file an episode is transcribed from unless another is chosen: its full audio, else its full video */
export function defaultFile(episode: Pick<EpisodeFiles, 'media'>) {
  return episode.media.find((m) => m.kind === 'FULL_AUDIO') ?? episode.media.find((m) => m.kind === 'FULL_VIDEO') ?? null
}

/** The languages an episode is transcribed as: Ogrodje's are in Slovenian, Borderless's in English, as the team had it */
export const LANGUAGES = [
  { value: 'sl-SI', title: 'Slovenian' },
  { value: 'en-GB', title: 'English' },
]

/** Which of them an episode is in, by its language: English for any English, else Slovenian */
export function languageOf(episode: Pick<EpisodeFiles, 'language'>) {
  return episode.language.toLowerCase().startsWith('en') ? 'en-GB' : 'sl-SI'
}

/** Whether an episode's file has a transcript by the provider already, which transcribing it again replaces */
export function hasTranscript(episode: Pick<EpisodeFiles, 'transcripts'>, mediaId: string, provider: string) {
  return episode.transcripts.some((t) => t.mediaId === mediaId && t.provider === provider)
}

/** What a transcription is of, for finding it by what's typed: its episode's code and title, and the file's name */
export function transcriptionText(transcription: Pick<Transcription, 'episode' | 'mediaName'>) {
  return [transcription.episode?.code, transcription.episode?.title, transcription.mediaName].filter(Boolean).join(' ')
}

/** A transcript of one of an episode's files, as its page lists them, without what it says */
export interface EpisodeTranscript extends TranscriptSummary {
  mediaId: string
  mediaName: string
  provider: string
  model: string
  language: string
  createdAt: string
}

/** An episode's transcripts, the latest first, for its page */
export const EPISODE_TRANSCRIPT_FIELDS =
  'transcripts { id mediaId mediaName provider model language createdAt segments words duration speakers { label name } }'

/** What was said by one speaker in a row, from where they started; without speakers (Google's), a line each */
export interface TranscriptTurn {
  speaker: string | null
  start: number
  text: string
}

/** A transcript's lines as a conversation reads: a speaker's lines one after another are one turn */
export function turns(lines: TranscriptLine[]): TranscriptTurn[] {
  const found: TranscriptTurn[] = []
  for (const line of lines) {
    const last = found.at(-1)
    if (last && line.speaker != null && last.speaker === line.speaker) last.text = `${last.text} ${line.text}`
    else found.push({ speaker: line.speaker, start: line.start, text: line.text })
  }
  return found
}

/** A YouTube video's address at [seconds] into it, e.g. where a line of its transcript was said */
export function youtubeAt(url: string, seconds: number) {
  const at = new URL(url)
  at.searchParams.set('t', String(Math.floor(seconds)))
  return at.toString()
}

/**
 * The transcript an episode's page shows first: the latest that tells speakers apart (Soniox's, which also punctuates),
 * else the latest
 */
export function firstTranscript<T extends Pick<TranscriptSummary, 'speakers'>>(transcripts: T[]): T | undefined {
  return transcripts.find((transcript) => transcript.speakers.length) ?? transcripts[0]
}

/** What an episode's transcripts are, in a line, e.g. Soniox, 2 speakers, 8,445 words · also by Google Speech-to-Text */
export function transcriptsLine(transcripts: EpisodeTranscript[]) {
  const shown = firstTranscript(transcripts)
  if (!shown) return 'None yet'
  const others = [...new Set(transcripts.filter((t) => t.provider !== shown.provider).map((t) => providerName(t.provider)))]
  return [
    [
      providerName(shown.provider),
      shown.speakers.length ? `${shown.speakers.length} ${shown.speakers.length === 1 ? 'speaker' : 'speakers'}` : null,
      `${shown.words.toLocaleString('en-GB')} ${shown.words === 1 ? 'word' : 'words'}`,
    ].filter(Boolean).join(', '),
    others.length ? `also by ${others.join(' and ')}` : null,
  ].filter(Boolean).join(' · ')
}

/** When, as the page says it, in English and Ogrodje's time zone, e.g. 28 Oct, 14:02 */
export function formatWhen(at: string) {
  return new Date(at).toLocaleString(DATE_LOCALE, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })
}
