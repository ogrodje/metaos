import { useRuntimeConfig } from '#imports'
import { useAuthStore } from '@/stores/auth'

/** What went wrong, for the page to say */
export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}

/** A file from MetaOS that needs the sign-in token, e.g. a thumbnail, as a URL to show it from memory; revoke it after */
export async function fetchObjectUrl(path: string): Promise<string> {
  const token = await useAuthStore().token()
  const response = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  if (!response.ok) throw new Error(statusMessage(response.status))
  return URL.createObjectURL(await response.blob())
}

/** What MetaOS's answer with [status] means for the user, e.g. that it's restarting */
function statusMessage(status: number) {
  if (status === 502 || status === 503 || status === 504) {
    return `MetaOS isn't answering (${status}); it may be restarting`
  }
  return `MetaOS answered ${status}`
}

/** A GraphQL error as the user can act on it: who may see what, rather than "Unauthorized" */
function graphqlMessage(error: { message: string, extensions?: { classification?: string } }) {
  switch (error.extensions?.classification) {
    case 'UNAUTHORIZED':
    case 'FORBIDDEN':
      return "your account isn't approved for this yet; an admin approves new accounts"
    default:
      return error.message
  }
}

// How long the server waits for MetaOS before it renders the page with what didn't load, which the browser then loads
const SERVER_TIMEOUT = 10_000

/**
 * Asks MetaOS's GraphQL API (/graphql), as the signed-in user when there is one. On the server, while it renders a
 * page, as someone who isn't signed in, straight from MetaOS (its runtime config's metaosUrl): that page is everyone's.
 */
export async function graphql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
  // Read before anything is awaited, while Nuxt knows which request it's for
  const url = import.meta.server ? `${useRuntimeConfig().metaosUrl}/graphql` : '/graphql'
  const token = import.meta.server ? undefined : await useAuthStore().token()
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // Errors come in the body, with a 200
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ query, variables }),
    signal: import.meta.server ? AbortSignal.timeout(SERVER_TIMEOUT) : undefined,
  }).catch((error) => {
    // Offline, or MetaOS can't be reached at all
    throw new Error("MetaOS can't be reached; check the connection", { cause: error })
  })
  if (!response.ok) throw new Error(statusMessage(response.status))

  const { data, errors } = await response.json()
  if (errors?.length) throw new Error([...new Set(errors.map(graphqlMessage))].join('; '))
  return data as T
}
