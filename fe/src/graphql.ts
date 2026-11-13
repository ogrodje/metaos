import { useRuntimeConfig } from '#imports'
import type { Translate } from '@/i18n'
import { useAuthStore } from '@/stores/auth'

/**
 * Something that went wrong as the page says it, in its language: a message key (locales: errors.*) and what goes in
 * it, which errorMessage() translates; MetaOS's own words, e.g. a GraphQL error's, are [detail], as MetaOS gave them
 */
export class UserError extends Error {
  constructor(readonly key: string, readonly params: Record<string, unknown> = {}, options?: ErrorOptions) {
    super(key, options)
  }
}

/** What went wrong, for the page to say, in its language */
export function errorMessage(error: unknown, t: Translate) {
  if (error instanceof UserError) return t(error.key, error.params)
  return error instanceof Error ? error.message : String(error)
}

/** A file from MetaOS that needs the sign-in token, e.g. a thumbnail, as a URL to show it from memory; revoke it after */
export async function fetchObjectUrl(path: string): Promise<string> {
  const token = await useAuthStore().token()
  const response = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  if (!response.ok) throw statusError(response.status)
  return URL.createObjectURL(await response.blob())
}

/** What MetaOS's answer with [status] means for the user, e.g. that it's restarting */
function statusError(status: number) {
  return new UserError(status === 502 || status === 503 || status === 504 ? 'errors.restarting' : 'errors.status', { status })
}

/** GraphQL's errors as the user can act on them: who may see what, rather than "Unauthorized"; else MetaOS's words */
function graphqlError(errors: { message: string, extensions?: { classification?: string } }[]) {
  const denied = errors.some((error) => ['UNAUTHORIZED', 'FORBIDDEN'].includes(error.extensions?.classification ?? ''))
  if (denied) return new UserError('errors.staffOnly')
  return new UserError('errors.graphql', { detail: [...new Set(errors.map((error) => error.message))].join('; ') })
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
    throw new UserError('errors.unreachable', {}, { cause: error })
  })
  if (!response.ok) throw statusError(response.status)

  const { data, errors } = await response.json()
  if (errors?.length) throw graphqlError(errors)
  return data as T
}
