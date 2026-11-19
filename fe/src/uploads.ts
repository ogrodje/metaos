import { type ArtworkKind, type Picked } from '@/artwork'
import { errorMessage, graphql, UserError } from '@/graphql'
import type { Translate } from '@/i18n'
import { type Image, IMAGE_FIELDS } from '@/images'

// Pictures sent from the browser to MetaOS's bucket, and made an episode's, a person's avatar or an organisation's logo
// (ImageUploads.kt): see artwork.ts for which of an episode's pictures may be which

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

/** Where MetaOS says to send a file: a resumable upload (SignedUpload in Storage.kt), by its ID for MetaOS */
export interface SignedUpload {
  id: string
  url: string
  headers: { name: string, value: string }[]
}

/** Starts a resumable upload, and says where the file goes: the upload's session, which takes it whole or in chunks */
export async function startUpload(upload: SignedUpload, signal: AbortSignal): Promise<string> {
  const start = await fetch(upload.url, {
    method: 'POST',
    headers: Object.fromEntries(upload.headers.map((header) => [header.name, header.value])),
    signal,
  }).catch((error) => {
    if (signal.aborted) throw error
    throw new UserError('media.upload.unreachable', {}, { cause: error })
  })
  const session = start.ok ? start.headers.get('Location') : null
  if (!session) throw new UserError('media.upload.notTaken', { status: start.status })
  return session
}

/** What MetaOS takes as a picture: an episode's are PNG or JPEG of their sizes, avatars and logos any of these */
export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']

/**
 * Sends a picture to MetaOS's bucket: MetaOS says where to send it, the browser starts a resumable upload there
 * (Storage.kt) and sends the file. The upload's ID, which a mutation then makes the picture of something. [signal]
 * stops it while it's being sent.
 */
export async function uploadImage(
  file: File, type: string, onProgress: (progress: Progress) => void, signal: AbortSignal,
): Promise<string> {
  const { imageUpload } = await graphql<{ imageUpload: SignedUpload }>(
    `mutation ($contentType: String!, $size: Int!) {
      imageUpload(contentType: $contentType, size: $size) { id url headers { name value } }
    }`, { contentType: type, size: file.size },
  )
  onProgress({ state: 'uploading', percent: 0 })
  const session = await startUpload(imageUpload, signal)
  await send(session, file, type, (percent) => onProgress({ state: 'uploading', percent }), signal)
  onProgress({ state: 'publishing' })
  return imageUpload.id
}

/**
 * Sends a picture to MetaOS's bucket and makes it one of the episode's, which MetaOS publishes. [replace] lets it
 * replace another picture the episode has there.
 */
export async function uploadArtwork(
  ref: string, kind: ArtworkKind, picked: Picked, replace: boolean, onProgress: (progress: Progress) => void, signal: AbortSignal,
): Promise<Uploaded> {
  const upload = await uploadImage(picked.file, picked.type ?? 'image/png', onProgress, signal)
  const { setEpisodeImage } = await graphql<{ setEpisodeImage: Uploaded }>(
    `mutation ($ref: String!, $kind: EpisodeImage!, $upload: ID!, $name: String!, $replace: Boolean!) {
      setEpisodeImage(ref: $ref, kind: $kind, upload: $upload, name: $name, replace: $replace) {
        image { ${IMAGE_FIELDS} } dropbox { path copied error }
      }
    }`, { ref, kind, upload, name: picked.name, replace },
  )
  return setEpisodeImage
}

/** Takes one of the episode's pictures off it */
export async function removeArtwork(ref: string, kind: ArtworkKind): Promise<void> {
  await graphql(`mutation ($ref: String!, $kind: EpisodeImage!) { removeEpisodeImage(ref: $ref, kind: $kind) { sha256 } }`, { ref, kind })
}

/** What a picture is of, besides an episode: a person, as their avatar, or an organisation, as its logo */
export type PictureOf = { person: string } | { organisation: string }

/** Sends a picture, of any size, and makes it the person's avatar or the organisation's logo */
export async function uploadPicture(
  of: PictureOf, file: File, onProgress: (progress: Progress) => void, signal: AbortSignal,
): Promise<Image> {
  if (!IMAGE_TYPES.includes(file.type)) throw new UserError('pictures.unsupported', { name: file.name })
  const upload = await uploadImage(file, file.type, onProgress, signal)
  if ('person' in of) {
    const { setPersonAvatar } = await graphql<{ setPersonAvatar: Image }>(
      `mutation ($id: ID!, $upload: ID!, $name: String!) { setPersonAvatar(personId: $id, upload: $upload, name: $name) { ${IMAGE_FIELDS} } }`,
      { id: of.person, upload, name: file.name },
    )
    return setPersonAvatar
  }
  const { setOrganisationLogo } = await graphql<{ setOrganisationLogo: Image }>(
    `mutation ($id: ID!, $upload: ID!, $name: String!) { setOrganisationLogo(organisationId: $id, upload: $upload, name: $name) { ${IMAGE_FIELDS} } }`,
    { id: of.organisation, upload, name: file.name },
  )
  return setOrganisationLogo
}

/** Takes the person's avatar or the organisation's logo off them */
export async function removePicture(of: PictureOf): Promise<void> {
  if ('person' in of) {
    await graphql(`mutation ($id: ID!) { removePersonAvatar(personId: $id) { sha256 } }`, { id: of.person })
  } else {
    await graphql(`mutation ($id: ID!) { removeOrganisationLogo(organisationId: $id) { sha256 } }`, { id: of.organisation })
  }
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
      else reject(new UserError('media.upload.answered', { status: request.status }))
    }
    request.onerror = () => reject(new UserError('media.upload.broke'))
    request.onabort = () => reject(new DOMException('Stopped', 'AbortError'))
    signal.addEventListener('abort', () => request.abort(), { once: true })
    request.send(file)
  })
}

/** Whether an upload was stopped, rather than failed */
export const stopped = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'

/** What went wrong with an upload, for its row */
export const uploadError = (error: unknown, t: Translate) => errorMessage(error, t).replace(/\.$/, '') + '.'
