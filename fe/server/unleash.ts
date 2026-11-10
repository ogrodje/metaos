import { type H3Event, getRequestHeader, setResponseHeader, setResponseStatus } from 'h3'
import { useRuntimeConfig } from 'nitropack/runtime'

// How long Unleash may take: a page the server renders waits for it, and goes on with the flags' defaults after
const TIMEOUT = 1500

/**
 * Asks Unleash's frontend API (NUXT_UNLEASH_URL) at [path] with the front-end's token (NUXT_UNLEASH_TOKEN), which never
 * leaves the server, and answers as it did: so Unleash stays in the cluster, and the browser asks this origin. 503
 * without a token, 502 when Unleash doesn't answer; the flags are their defaults then (plugins/flags.ts).
 */
export async function askUnleash(event: H3Event, path: string, init: { method?: string, body?: string } = {}) {
  setResponseHeader(event, 'cache-control', 'no-store')
  const { unleashUrl, unleashToken } = useRuntimeConfig(event)
  if (!unleashToken) {
    setResponseStatus(event, 503)
    return { error: 'Feature flags are off: the front-end has no token for Unleash (NUXT_UNLEASH_TOKEN)' }
  }
  const headers: Record<string, string> = { authorization: unleashToken, accept: 'application/json' }
  const etag = getRequestHeader(event, 'if-none-match')
  if (etag) headers['if-none-match'] = etag
  if (init.body !== undefined) headers['content-type'] = 'application/json'
  try {
    const response = await fetch(unleashUrl + path, { ...init, headers, signal: AbortSignal.timeout(TIMEOUT) })
    if (response.status >= 500 || response.status === 401 || response.status === 403) {
      console.error(`Unleash answered ${response.status} for ${path.split('?')[0] || '/'}`)
      setResponseStatus(event, 502)
      return { error: `Unleash answered ${response.status}` }
    }
    setResponseStatus(event, response.status)
    const answered = response.headers.get('etag')
    if (answered) setResponseHeader(event, 'etag', answered)
    const text = await response.text()
    if (!text) return null
    setResponseHeader(event, 'content-type', 'application/json')
    return text
  } catch (error) {
    console.error('Unleash did not answer', error)
    setResponseStatus(event, 502)
    return { error: "Unleash didn't answer" }
  }
}
