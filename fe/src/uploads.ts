import { type ArtworkKind, type Picked } from '@/artwork'
import { errorMessage, graphql } from '@/graphql'
import { type Image, IMAGE_FIELDS } from '@/images'

// Pictures sent from the browser to MetaOS's bucket, and made an episode's (Artwork.kt): see artwork.ts for which
// pictures may be which

/** Where an uploaded picture is in the episode's Dropbox folder, or why it isn't */
export interface DropboxCopy {
  path: string | null
  copied: boolean
  error: string | null
}

export interface Uploaded {
  image: Image
  dropbox: DropboxCopy | null
}

export type Progress = { state: 'uploading', percent: number } | { state: 'publishing' }

interface ArtworkUpload {
  id: string
  url: string
  headers: { name: string, value: string }[]
}

/**
 * Sends a picture to MetaOS's bucket and makes it one of the episode's: MetaOS says where to send it, the browser starts
 * a resumable upload there (Storage.kt) and sends the file, then MetaOS publishes it. [replace] lets it replace another
 * picture the episode has there. [signal] stops it while it's being sent.
 */
export async function uploadArtwork(
  ref: string, kind: ArtworkKind, picked: Picked, replace: boolean, onProgress: (progress: Progress) => void, signal: AbortSignal,
): Promise<Uploaded> {
  const type = picked.type ?? 'image/png'
  const { artworkUpload } = await graphql<{ artworkUpload: ArtworkUpload }>(
    `mutation ($ref: String!, $contentType: String!, $size: Int!) {
      artworkUpload(ref: $ref, contentType: $contentType, size: $size) { id url headers { name value } }
    }`, { ref, contentType: type, size: picked.file.size },
  )
  onProgress({ state: 'uploading', percent: 0 })
  const start = await fetch(artworkUpload.url, {
    method: 'POST',
    headers: Object.fromEntries(artworkUpload.headers.map((header) => [header.name, header.value])),
    signal,
  }).catch((error) => {
    if (signal.aborted) throw error
    throw new Error("Google Cloud Storage can't be reached; check the connection", { cause: error })
  })
  const session = start.ok ? start.headers.get('Location') : null
  if (!session) throw new Error(`Google Cloud Storage didn't take it (${start.status})`)
  await send(session, picked.file, type, (percent) => onProgress({ state: 'uploading', percent }), signal)
  onProgress({ state: 'publishing' })
  const { setEpisodeImage } = await graphql<{ setEpisodeImage: Uploaded }>(
    `mutation ($ref: String!, $kind: EpisodeImage!, $upload: ID!, $name: String!, $replace: Boolean!) {
      setEpisodeImage(ref: $ref, kind: $kind, upload: $upload, name: $name, replace: $replace) {
        image { ${IMAGE_FIELDS} } dropbox { path copied error }
      }
    }`, { ref, kind, upload: artworkUpload.id, name: picked.name, replace },
  )
  return setEpisodeImage
}

// With XMLHttpRequest, as fetch doesn't tell how much of a body it has sent
function send(url: string, file: File, type: string, onProgress: (percent: number) => void, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', url)
    request.setRequestHeader('Content-Type', type)
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100))
    }
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) resolve()
      else reject(new Error(`Google Cloud Storage answered ${request.status}`))
    }
    request.onerror = () => reject(new Error('The connection broke while it was being sent'))
    request.onabort = () => reject(new DOMException('Stopped', 'AbortError'))
    signal.addEventListener('abort', () => request.abort(), { once: true })
    request.send(file)
  })
}

/** Whether an upload was stopped, rather than failed */
export const stopped = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'

/** What went wrong with an upload, for its row */
export const uploadError = (error: unknown) => errorMessage(error).replace(/\.$/, '') + '.'
