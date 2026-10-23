// Episodes as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls)

import type { Comment } from '@/comments'
import { type Image, IMAGE_FIELDS } from '@/images'

// A candidate could be the episode's guest, until they're confirmed as one; only signed-in users get them
export type Role = 'HOST' | 'CO_HOST' | 'GUEST' | 'PRODUCER' | 'CONSULTANT' | 'DESIGNER' | 'CANDIDATE'

export type EpisodeStatus =
  | 'IDEA'
  | 'PLANNED'
  | 'SCHEDULED'
  | 'RECORDED'
  | 'IN_PRODUCTION'
  | 'READY'
  | 'EARLY_ACCESS'
  | 'PUBLISHED'
  | 'ON_HOLD'
  | 'CANCELLED'

export interface EpisodePerson {
  personId: string
  name: string
  roles: Role[]
}

/** A Google Doc linked to an episode or a session; only signed-in users get them */
export interface GoogleDoc {
  fileId: string
  url: string
}

export interface Session {
  id: string
  date: string
  location: string | null
  documents: GoogleDoc[]
  koofrFolders: KoofrFolderLink[]
  dropboxFolders: DropboxFolderLink[]
}

export interface Episode {
  id: string
  code: string | null
  title: string
  show: string | null
  // Its show's, as hex RGB
  showColor: string | null
  status: EpisodeStatus
  session: Session | null
  recordingDate: string | null
  airedDate: string | null
  youtubeUrl: string | null
  spotifyUrl: string | null
  applePodcastsUrl: string | null
  patreonUrl: string | null
  // Markdown, e.g. a sentence
  tinySummary: string | null
  people: EpisodePerson[]
  documents: GoogleDoc[]
  koofrFolders: KoofrFolderLink[]
  dropboxFolders: DropboxFolderLink[]
}

/** A photo in a Koofr folder: its thumbnail, from MetaOS with the sign-in token, and its folder in Koofr */
export interface KoofrPhoto {
  name: string
  path: string
  thumbnail: string
  url: string
}

/** A Koofr folder shared with Ogrodje's bot: a place, by name when Koofr answers, a path in it, and where it opens */
export interface KoofrFolder {
  place: string
  placeName: string | null
  path: string
  url: string
  photos: KoofrPhoto[]
}

/** A Koofr folder as lists have it, for a link; only signed-in users get them */
export type KoofrFolderLink = Pick<KoofrFolder, 'path' | 'url'>

/**
 * A Dropbox folder in a folder shared with Ogrodje's bot: where it is now, as it's linked by its Dropbox ID, and where
 * it opens; missing once it's gone from there
 */
export interface DropboxFolder {
  place: string
  placeName: string | null
  path: string
  missing: boolean
  url: string
}

/** A Dropbox folder as lists have it, for a link; only signed-in users get them */
export type DropboxFolderLink = Pick<DropboxFolder, 'path' | 'url'>

/** A picture in a Dropbox folder, e.g. artwork: its thumbnail, from MetaOS with the sign-in token, and its folder's link */
export interface DropboxPicture {
  name: string
  path: string
  thumbnail: string
  url: string
}

/** A Dropbox folder with the first pictures in it, e.g. an episode's artwork */
export interface DropboxFolderWithPictures extends DropboxFolder {
  pictures: DropboxPicture[]
}

/** One field of a change; from and to are null when there's nothing short to show, e.g. a summary */
export interface FieldChange {
  field: string
  from: string | null
  to: string | null
}

/** A change from the audit log, told as what happened */
export interface Change {
  at: string
  actor: string | null
  client: string | null
  tool: string | null
  summary: string
  fields: FieldChange[]
}

/** A person, as an episode names who recommended it; their page is at their ID */
export interface PersonName {
  id: string
  name: string
}

/** An episode idea, as the Ideas page lists them: with when it was added, as they're listed the newest first */
export interface Idea extends Episode {
  addedDate: string
  recommendedBy: PersonName | null
}

/** An episode with everything about it, for its page */
export interface EpisodeDetails extends Episode {
  // What the title is A/B tested against
  titleB: string | null
  titleC: string | null
  // 16:9, and those that go with titles B and C, as they're tested together
  thumbnail: Image | null
  thumbnailB: Image | null
  thumbnailC: Image | null
  // Square, e.g. for podcast apps
  cover: Image | null
  summary: string | null
  machineSummary: string | null
  addedDate: string
  recommendedBy: PersonName | null
  koofrFolders: KoofrFolder[]
  dropboxFolders: DropboxFolderWithPictures[]
  history: Change[]
  comments: Comment[]
}

/** A session with everything about it, for its page */
export interface SessionDetails extends Session {
  address: string | null
  episodes: Episode[]
  koofrFolders: KoofrFolder[]
  dropboxFolders: DropboxFolder[]
  history: Change[]
  comments: Comment[]
}

// All the photos, as the page shows them a few at a time; their thumbnails it fetches only as they're shown
export const KOOFR_FIELDS = 'koofrFolders { place placeName path url photos { name path thumbnail url } }'
export const DROPBOX_FIELDS = 'dropboxFolders { place placeName path missing url }'
// An episode's artwork: all the pictures in its Dropbox folders
export const ARTWORK_FIELDS = 'dropboxFolders { pictures { name path thumbnail url } }'

/** The photos of an episode's or session's Koofr folders */
export function photosOf(folders: KoofrFolder[]) {
  return folders.flatMap((folder) => folder.photos)
}

/** The pictures of an episode's Dropbox folders, its artwork */
export function artworkOf(folders: DropboxFolderWithPictures[]) {
  return folders.flatMap((folder) => folder.pictures)
}
export const HISTORY_FIELDS = 'history { at actor client tool summary fields { field from to } }'
// An episode's thumbnails and cover, from MetaOS's public bucket
export const PICTURE_FIELDS = ['thumbnail', 'thumbnailB', 'thumbnailC', 'cover'].map((kind) => `${kind} { ${IMAGE_FIELDS} }`).join(' ')

/** An episode's page: at its code, or at its ID while it has none */
export function episodePath(episode: Pick<Episode, 'id' | 'code'>) {
  return `/episodes/${encodeURIComponent(episode.code ?? episode.id)}`
}

export function sessionPath(session: Pick<Session, 'id'>) {
  return `/sessions/${session.id}`
}

/** The fields of an Episode, for a query */
export const EPISODE_FIELDS = `
  id code title show showColor status recordingDate airedDate youtubeUrl spotifyUrl applePodcastsUrl patreonUrl
  tinySummary
  session { id date location documents { fileId url } koofrFolders { path url } dropboxFolders { path url } }
  people { personId name roles }
  documents { fileId url }
  koofrFolders { path url }
  dropboxFolders { path url }
`

const platforms = {
  YouTube: 'youtubeUrl',
  Spotify: 'spotifyUrl',
  'Apple Podcasts': 'applePodcastsUrl',
  Patreon: 'patreonUrl',
} as const

export type Platform = keyof typeof platforms

export function links(episode: Pick<Episode, (typeof platforms)[Platform]>) {
  return (Object.keys(platforms) as Platform[]).flatMap((platform) => {
    const url = episode[platforms[platform]]
    return url ? [{ platform, url }] : []
  })
}

// CO_HOST -> co-host
export function roleName(role: Role) {
  return role.toLowerCase().replace('_', '-')
}

// IN_PRODUCTION -> In production
export function statusName(status: EpisodeStatus) {
  return status.charAt(0) + status.slice(1).toLowerCase().replace('_', ' ')
}

function named(episode: Pick<Episode, 'people'>, role: Role) {
  return episode.people.filter((person) => person.roles.includes(role)).map((person) => person.name)
}

/** The names of an episode's guests, whom a list of episodes names instead of everyone on it */
export function guests(episode: Pick<Episode, 'people'>) {
  return named(episode, 'GUEST')
}

/** The names of those who could be an episode's guests, e.g. an idea's */
export function candidates(episode: Pick<Episode, 'people'>) {
  return named(episode, 'CANDIDATE')
}

// Dates as the rest of the page is written, in English, e.g. 22 Oct 2026, whatever the browser's language, and in
// Ogrodje's time zone, wherever the browser is: the server renders them so too, and the browser's have to match
export const DATE_LOCALE = 'en-GB'
export const TIME_ZONE = 'Europe/Ljubljana'

export function formatDate(date: string) {
  return new Date(date).toLocaleDateString(DATE_LOCALE, { dateStyle: 'medium', timeZone: TIME_ZONE })
}

const DAY = 24 * 60 * 60 * 1000

/** Days from [today] to [date], both as calendar days where the user is: 1 is tomorrow, -1 yesterday */
export function daysFrom(today: Date, date: string) {
  const [year, month, day] = date.slice(0, 10).split('-').map(Number)
  const then = Date.UTC(year!, month! - 1, day!)
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((then - now) / DAY)
}

const relative = new Intl.RelativeTimeFormat(DATE_LOCALE, { numeric: 'auto' })

/** How far [date] is from [today], e.g. tomorrow, in 5 days, 3 weeks ago, 4 months ago */
export function relativeDate(date: string, today = new Date()) {
  const days = daysFrom(today, date)
  const size = Math.abs(days)
  if (size < 14) return relative.format(days, 'day')
  if (size < 60) return relative.format(Math.trunc(days / 7), 'week')
  return relative.format(Math.trunc(days / 30), 'month')
}

/** A session as people know it: when and where, e.g. 22 Oct 2026 · AWS Community Day Adria */
export function sessionTitle(session: Pick<Session, 'date' | 'location'>) {
  return [formatDate(session.date), session.location].filter(Boolean).join(' · ')
}

export interface SessionGroup {
  key: string
  session: Session | null
  episodes: Episode[]
}

/**
 * Episodes by their recording session, in the order they come in. Those without one come first, together, so that
 * when they're listed without a heading they don't look like part of the session above them.
 */
export function bySession(episodes: Episode[]): SessionGroup[] {
  const groups = new Map<string, SessionGroup>()
  for (const episode of episodes) {
    const key = episode.session?.id ?? 'none'
    const group = groups.get(key) ?? { key, session: episode.session, episodes: [] }
    group.episodes.push(episode)
    groups.set(key, group)
  }
  return [...groups.values()].sort((a, b) => Number(a.session !== null) - Number(b.session !== null))
}
