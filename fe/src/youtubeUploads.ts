import { graphql } from '@/graphql'
import { UPLOAD_FIELDS, type YouTubeUpload } from '@/youtube'

// Sending an episode's video to YouTube and stopping it: MetaOS's GraphQL uploadToYouTube and cancelYouTubeUpload

/** Starts sending one of the episode's video files to YouTube, as a private video; what's left out is the episode's own */
export async function uploadToYouTube(ref: string, media: string, details: { title?: string, description?: string }): Promise<YouTubeUpload> {
  const { upload } = await graphql<{ upload: YouTubeUpload }>(
    `mutation ($ref: String!, $media: ID, $title: String, $description: String) {
      upload: uploadToYouTube(ref: $ref, media: $media, title: $title, description: $description) { ${UPLOAD_FIELDS} }
    }`, { ref, media, title: details.title || null, description: details.description ?? null },
  )
  return upload
}

/** Stops an upload that's on; a video that's on YouTube by then stays there, private */
export async function cancelYouTubeUpload(id: string): Promise<YouTubeUpload> {
  const { upload } = await graphql<{ upload: YouTubeUpload }>(
    `mutation ($id: ID!) { upload: cancelYouTubeUpload(id: $id) { ${UPLOAD_FIELDS} } }`, { id },
  )
  return upload
}
