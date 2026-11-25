import { type DateLocale, TIME_ZONE, type Translate } from '@/i18n'
import { matches } from '@/lists'
import { formatDuration } from '@/media'
import { isOn, type YouTubePrivacy, type YouTubeUploadStatus } from '@/youtube'

// Ogrodje's YouTube channel as its page lists it (YouTubeView.vue): every video MetaOS knows there, with whose it is,
// and the uploads of episodes' files; MetaOS's GraphQL youtube, youtubeVideos and youtubeUploads. With no Nuxt in it, as
// the unit tests load it. What an episode's page says of its own videos is youtube.ts's.

/** Which channel MetaOS is connected to, and whether a look at it that someone asked for is on */
export interface YouTubeChannel {
  connected: boolean
  channelId: string | null
  channel: string | null
  handle: string | null
  refreshing: boolean
  error: string | null
}

interface ItsEpisode {
  id: string
  code: string | null
  title: string
}

/** One of the channel's videos, as YouTube said it was when MetaOS last looked */
export interface ChannelVideo {
  id: string
  videoId: string
  url: string
  studioUrl: string
  title: string
  description: string
  tags: string[]
  privacy: YouTubePrivacy
  // YouTube's: uploaded (and being processed), processed, failed, rejected or deleted
  uploadStatus: string | null
  // When a private one is scheduled to go public
  publishAt: string | null
  publishedAt: string | null
  // Seconds; none until YouTube has processed it
  duration: number | null
  live: boolean
  thumbnailUrl: string | null
  episode: ItsEpisode | null
  // The episode's file MetaOS uploaded it from, if it did
  mediaId: string | null
}

/** One of an episode's video files being sent to YouTube, or that was */
export interface ChannelUpload {
  id: string
  episode: ItsEpisode | null
  mediaId: string
  mediaName: string
  title: string
  status: YouTubeUploadStatus
  queued: number | null
  percent: number | null
  error: string | null
  note: string | null
  videoId: string | null
  video: { studioUrl: string } | null
  requestedBy: string | null
  createdAt: string
}

const VIDEO_FIELDS = 'id videoId url studioUrl title description tags privacy uploadStatus publishAt publishedAt duration live '
  + 'thumbnailUrl mediaId episode { id code title }'

/** The page's data: the channel, its videos (MetaOS lists at most 500) and the latest uploads */
export const CHANNEL = `{
  youtube { connected channelId channel handle refreshing error }
  youtubeVideos(limit: 500) { ${VIDEO_FIELDS} }
  youtubeUploads(limit: 30) {
    id mediaId mediaName title status queued percent error note videoId requestedBy createdAt
    episode { id code title } video { studioUrl }
  }
}`

export const REFRESH = 'mutation { youtube: refreshYouTubeVideos { connected channelId channel handle refreshing error } }'

export const SET_EPISODE = `mutation ($id: String!, $episode: String) {
  video: setYouTubeVideoEpisode(id: $id, episode: $episode) { ${VIDEO_FIELDS} }
}`

export const UPDATE_VIDEO = `mutation ($id: String!, $title: String, $description: String, $tags: [String!]) {
  video: updateYouTubeVideo(id: $id, title: $title, description: $description, tags: $tags) { ${VIDEO_FIELDS} }
}`

/** YouTube's limits on what a video says about itself, as MetaOS checks them (YouTube.kt) */
export const MAX_TITLE = 100
export const MAX_DESCRIPTION_BYTES = 5000
export const MAX_TAGS = 500

// A Short is up to three minutes; YouTube's API doesn't say which videos are, so a short one that isn't a stream is taken
// for one
const SHORT_SECONDS = 180

export type VideoKind = 'video' | 'short'

/** Whether it's a video or, by its length, a Short; one YouTube hasn't measured yet is a video */
export const kindOf = (video: Pick<ChannelVideo, 'duration' | 'live'>): VideoKind =>
  !video.live && video.duration != null && video.duration <= SHORT_SECONDS ? 'short' : 'video'

export type KindFilter = 'all' | VideoKind
export type PrivacyFilter = 'all' | 'public' | 'unlisted' | 'private'
export type EpisodeFilter = 'all' | 'with' | 'without'

export interface VideoFilters {
  kind: KindFilter
  privacy: PrivacyFilter
  episode: EpisodeFilter
  find: string
}

export const inKind = (video: ChannelVideo, kind: KindFilter) => kind === 'all' || kindOf(video) === kind
export const inPrivacy = (video: ChannelVideo, privacy: PrivacyFilter) => privacy === 'all' || video.privacy === privacy.toUpperCase()
export const inEpisode = (video: ChannelVideo, episode: EpisodeFilter) => episode === 'all' || (episode === 'with') === !!video.episode

/** What a video is found by: its title, its episode's code and title, its tags and YouTube's ID */
export const videoText = (video: ChannelVideo) =>
  [video.title, video.episode?.code, video.episode?.title, ...video.tags, video.videoId].filter(Boolean).join(' ')

/** The videos that pass every filter, in the order they came */
export const filterVideos = (videos: ChannelVideo[], filters: VideoFilters) => videos.filter((video) =>
  inKind(video, filters.kind) && inPrivacy(video, filters.privacy) && inEpisode(video, filters.episode)
  && matches(videoText(video), filters.find))

/** A day, e.g. 24 Nov 2026 */
export const formatDay = (at: string, dateLocale: DateLocale) =>
  new Date(at).toLocaleDateString(dateLocale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: TIME_ZONE })

/**
 * What a video is, in a line: a stream or a Short, its length, when it was uploaded or went public, and what YouTube
 * says of it while that's not "processed"
 */
export function videoLine(video: ChannelVideo, t: Translate, dateLocale: DateLocale): string {
  const parts: string[] = []
  if (video.live) parts.push(t('channel.kind.live'))
  else if (kindOf(video) === 'short') parts.push(t('channel.kind.short'))
  if (video.duration != null) parts.push(formatDuration(video.duration))
  if (video.publishedAt) parts.push(formatDay(video.publishedAt, dateLocale))
  if (video.uploadStatus && video.uploadStatus !== 'processed') parts.push(t('channel.uploadStatus', { status: video.uploadStatus }))
  return parts.join(' · ')
}

/** Tags as they're typed, with commas between them, and back */
export const tagsText = (tags: string[]) => tags.join(', ')
export const tagsOf = (text: string) => [...new Set(text.split(',').map((tag) => tag.trim().replace(/^#+/, '')).filter(Boolean))]

const bytes = (text: string) => new TextEncoder().encode(text).length

/** Why YouTube wouldn't take a video's details, as MetaOS would say too; null when it would */
export function detailsRefusal(details: { title: string, description: string, tags: string[] }, t: Translate): string | null {
  const title = details.title.trim()
  if (!title) return t('channel.edit.noTitle')
  if (title.length > MAX_TITLE) return t('channel.edit.longTitle', { max: MAX_TITLE })
  if (/[<>]/.test(title) || /[<>]/.test(details.description)) return t('channel.edit.brackets')
  if (bytes(details.description) > MAX_DESCRIPTION_BYTES) return t('channel.edit.longDescription', { max: MAX_DESCRIPTION_BYTES })
  // As YouTube counts them: a comma between tags, and quotes around one with a space
  const counted = details.tags.reduce((sum, tag) => sum + tag.length + (tag.includes(' ') ? 2 : 0), Math.max(0, details.tags.length - 1))
  if (counted > MAX_TAGS) return t('channel.edit.longTags', { max: MAX_TAGS })
  return null
}

/** The uploads the page shows: those on, then the latest few that ended, the latest first */
export function shownUploads(uploads: ChannelUpload[], ended = 5): { on: ChannelUpload[], ended: ChannelUpload[] } {
  const latestFirst = [...uploads].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  return { on: latestFirst.filter(isOn), ended: latestFirst.filter((upload) => !isOn(upload)).slice(0, ended) }
}
