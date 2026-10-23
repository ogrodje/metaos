import { useAsyncData } from '#imports'
import { errorMessage } from '@/graphql'

/** What a page first shows: its data, or why it didn't load */
export interface FirstLoad<T> {
  data?: T
  error?: string
}

/**
 * What a page shows first, by [key], which has to be unique to the page and what it's about, e.g. episode:S05E08.
 * Opening the page, the server loads it as someone who isn't signed in, renders it, and passes it on with the page,
 * which shows it while it's hydrated rather than loading it again; the page loads it again once the user is known
 * to be on the team (stores/auth.ts). Going to the page in the browser, it's loaded there, as the signed-in user.
 */
export async function useFirstLoad<T>(key: string, load: () => Promise<T>): Promise<FirstLoad<T>> {
  const { data, error } = await useAsyncData(key, load)
  return { data: data.value, error: error.value ? errorMessage(error.value) : undefined }
}
