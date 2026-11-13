import { useAsyncData, useI18n } from '#imports'
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
  // Why it didn't load is said where it's loaded, in the page's language: an error passed on with the page would come
  // as its message alone, which for a UserError is its message key (graphql.ts)
  const { t } = useI18n()
  const { data } = await useAsyncData(key, async (): Promise<FirstLoad<T>> => {
    try {
      return { data: await load() }
    } catch (error) {
      return { error: errorMessage(error, t) }
    }
  })
  return data.value ?? {}
}
