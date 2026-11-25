import type { Translate } from '@/i18n'

// An episode's videos on Ogrodje's YouTube channel and the uploads of its files there, as its page shows them with its
// video and audio: MetaOS's GraphQL Episode.youtubeVideos and youtubeUploads (youtubeUploads.ts sends and cancels, as
// upload_youtube_video and cancel_youtube_upload do over MCP), with no Nuxt in it, as the unit tests load it. MetaOS only ever uploads
// private videos: a person publishes them, in YouTube Studio.

export type YouTubePrivacy = 'PUBLIC' | 'UNLISTED' | 'PRIVATE'

/** How far an upload is: waiting for its turn, being sent, YouTube processing it; then done, failed or cancelled */
export type YouTubeUploadStatus = 'QUEUED' | 'UPLOADING' | 'PROCESSING' | 'DONE' | 'FAILED' | 'CANCELLED'

export interface YouTubeVideo {
  id: string
  videoId: string
  title: string
  privacy: YouTubePrivacy
  // YouTube's: uploaded (and being processed), processed, failed, rejected or deleted
  uploadStatus: string | null
  url: string
  studioUrl: string
  // The episode's file MetaOS uploaded it from, if it did
  mediaId: string | null
}

export interface YouTubeUpload {
  id: string
  mediaId: string
  status: YouTubeUploadStatus
  queued: number | null
  percent: number | null
  error: string | null
  note: string | null
  videoId: string | null
  createdAt: string
}

export const UPLOAD_FIELDS = 'id mediaId status queued percent error note videoId createdAt'

export const YOUTUBE_FIELDS = 'youtubeVideos { id videoId title privacy uploadStatus url studioUrl mediaId } '
  + `youtubeUploads { ${UPLOAD_FIELDS} }`

const ON: YouTubeUploadStatus[] = ['QUEUED', 'UPLOADING', 'PROCESSING']

/** Whether it's still on: waiting for its turn, being sent, or being processed */
export const isOn = (upload: Pick<YouTubeUpload, 'status'>) => ON.includes(upload.status)

/**
 * What YouTube has of one of the episode's files: the videos MetaOS made of it, the latest first, and its latest
 * upload while that's worth saying: on, or failed or cancelled with no video to show for it
 */
export function fileOnYouTube(fileId: string, videos: YouTubeVideo[], uploads: YouTubeUpload[]) {
  const made = videos.filter((video) => video.mediaId === fileId)
  const latest = uploads.filter((upload) => upload.mediaId === fileId).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
  const told = latest && (isOn(latest) || (latest.status !== 'DONE' && !made.some((video) => video.videoId === latest.videoId)) || latest.note)
  return { videos: made, upload: told ? latest : undefined }
}

/** How far an upload is, in a line: Waiting, 2nd in line; Uploading, 40 %; Processing on YouTube; why it failed */
export function uploadLine(upload: YouTubeUpload, t: Translate): string {
  switch (upload.status) {
    case 'QUEUED': return upload.queued ? t('youtube.upload.queuedAt', { n: upload.queued }) : t('youtube.upload.queued')
    case 'UPLOADING': return upload.percent == null ? t('youtube.upload.uploading') : t('youtube.upload.uploadingPercent', { percent: upload.percent })
    case 'PROCESSING': return t('youtube.upload.processing')
    case 'FAILED': return t('youtube.upload.failed', { error: upload.error ?? '' }).trim()
    case 'CANCELLED': return t('youtube.upload.cancelled')
    default: return upload.note ? t('youtube.upload.note', { note: upload.note }) : t('youtube.upload.done')
  }
}

/**
 * What's left to add in YouTube Studio for the episode's A/B test, in a line: its titles B and C and thumbnails B and C,
 * those it has. YouTube's API can't set a test up (it's Studio's "A/B testing" alone), so MetaOS sends title and thumbnail
 * A, and a person adds the rest there. Null for an episode with nothing to test.
 */
export function abTestLine(
  episode: { titleB?: string | null, titleC?: string | null, thumbnailB?: unknown, thumbnailC?: unknown }, t: Translate,
): string | null {
  const what = [
    episode.titleB ? t('youtube.test.title', { letter: 'B' }) : null,
    episode.titleC ? t('youtube.test.title', { letter: 'C' }) : null,
    episode.thumbnailB ? t('youtube.test.thumbnail', { letter: 'B' }) : null,
    episode.thumbnailC ? t('youtube.test.thumbnail', { letter: 'C' }) : null,
  ].filter(Boolean)
  return what.length ? t('youtube.test.line', { what: what.join(', ') }) : null
}

/** Who may see a video, in the page's language */
export const privacyName = (privacy: YouTubePrivacy, t: Translate) => t(`youtube.privacy.${privacy}`)
