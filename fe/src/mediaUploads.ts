import type { MediaInfo } from 'mediainfo.js'
import { graphql } from '@/graphql'
import { MEDIA_FILE_FIELDS, type MediaFile, type MediaKind, type Picked, probeOf, typeOf } from '@/media'
import { sendInChunks } from '@/resumable'
import { type SignedUpload, startUpload } from '@/uploads'

// An episode's video and audio, read in the browser before they're sent, and sent from it to MetaOS's bucket in chunks
// (MediaFiles.kt): see media.ts for which file may be which

let mediaInfo: Promise<MediaInfo<'object'>> | undefined

// MediaInfo in WebAssembly, loaded once a file is to be read, as it's 2.6 MB, and kept for the next
function loadMediaInfo() {
  mediaInfo ??= Promise.all([import('mediainfo.js'), import('mediainfo.js/MediaInfoModule.wasm?url')])
    .then(([{ default: mediaInfoFactory }, { default: wasm }]) => mediaInfoFactory({ format: 'object', locateFile: () => wasm }))
  mediaInfo.catch(() => (mediaInfo = undefined))
  return mediaInfo
}

// One file at a time, as a MediaInfo reads one at a time
let reading = Promise.resolve()

let ids = 0

/**
 * A file as the browser reads it: what's in it, from the parts of it MediaInfo needs, megabytes rather than the file.
 * Nothing when it isn't one MetaOS takes, or MediaInfo can't read it, which MetaOS will say once it's sent.
 */
export async function readMedia(file: File): Promise<Picked> {
  const read = reading.then(async () => {
    if (!typeOf(file.name, null)) return null
    try {
      const reader = await loadMediaInfo()
      return probeOf(await reader.analyzeData(file.size, async (size, offset) =>
        new Uint8Array(await file.slice(offset, offset + size).arrayBuffer())))
    } catch (error) {
      console.warn(`MediaInfo didn't read ${file.name}`, error)
      return null
    }
  })
  reading = read.then(() => undefined)
  const probe = await read
  return { id: ++ids, file, name: file.name, type: typeOf(file.name, probe), probe }
}

export type Progress = { state: 'uploading', percent: number } | { state: 'checking' }

/**
 * Sends a video or audio file to MetaOS's bucket and makes it one of the episode's: MetaOS says where to send it, the
 * browser starts a resumable upload there (Storage.kt) and sends the file in chunks, then MetaOS reads what's in it
 * (ffprobe) and keeps it. [replace] lets it replace the full video or audio the episode has; a bonus has a [title].
 * [signal] stops it while it's being sent.
 */
export async function uploadMedia(
  ref: string, kind: MediaKind, picked: Picked, title: string | null, replace: boolean,
  onProgress: (progress: Progress) => void, signal: AbortSignal,
): Promise<MediaFile> {
  const { mediaUpload } = await graphql<{ mediaUpload: SignedUpload }>(
    `mutation ($ref: String!, $kind: MediaKind!, $contentType: String!, $size: Float!) {
      mediaUpload(ref: $ref, kind: $kind, contentType: $contentType, size: $size) { id url headers { name value } }
    }`, { ref, kind, contentType: picked.type, size: picked.file.size },
  )
  onProgress({ state: 'uploading', percent: 0 })
  const session = await startUpload(mediaUpload, signal)
  await sendInChunks(session, picked.file, (sent) => onProgress({ state: 'uploading', percent: Math.floor((sent / picked.file.size) * 100) }), signal)
  onProgress({ state: 'checking' })
  const { addEpisodeMedia } = await graphql<{ addEpisodeMedia: MediaFile }>(
    `mutation ($ref: String!, $kind: MediaKind!, $upload: ID!, $name: String!, $title: String, $replace: Boolean!) {
      addEpisodeMedia(ref: $ref, kind: $kind, upload: $upload, name: $name, title: $title, replace: $replace) { ${MEDIA_FILE_FIELDS} }
    }`, { ref, kind, upload: mediaUpload.id, name: picked.name, title, replace },
  )
  return addEpisodeMedia
}

/** A link that downloads a file, for an hour, asked for as it's to be downloaded */
export async function downloadUrl(id: string): Promise<string> {
  const { mediaFile } = await graphql<{ mediaFile: { downloadUrl: string | null } | null }>(
    'query ($id: ID!) { mediaFile(id: $id) { downloadUrl } }', { id },
  )
  if (!mediaFile?.downloadUrl) throw new Error("The file isn't there anymore")
  return mediaFile.downloadUrl
}

/** Takes a file off the episode, and out of MetaOS's bucket */
export async function removeMedia(id: string): Promise<void> {
  await graphql('mutation ($id: ID!) { removeEpisodeMedia(id: $id) }', { id })
}

/** Changes a file's title, or what it is, within video or audio */
export async function updateMedia(id: string, change: { title?: string, kind?: MediaKind }): Promise<MediaFile> {
  const { updateEpisodeMedia } = await graphql<{ updateEpisodeMedia: MediaFile }>(
    `mutation ($id: ID!, $title: String, $kind: MediaKind) { updateEpisodeMedia(id: $id, title: $title, kind: $kind) { ${MEDIA_FILE_FIELDS} } }`,
    { id, ...change },
  )
  return updateEpisodeMedia
}
