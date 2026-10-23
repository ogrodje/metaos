import { defineNuxtPlugin, onNuxtReady, useState } from '#imports'
import { resync } from '@/changes'

// How old a page from the server may be before it loads its data again once it's shown: Nitro and Cloudflare keep the
// pages anyone may see for a minute, and serve them for longer while they're rendered again (nuxt.config.ts)
const STALE_AFTER = 30_000

export default defineNuxtPlugin((nuxtApp) => {
  // When the server rendered the page, which comes with it in the payload
  const renderedAt = useState('renderedAt', () => Date.now())
  if (import.meta.server || !nuxtApp.payload.serverRendered) return
  // What changed since, the page's live updates didn't hear: they load it again, as after their stream broke. Once the
  // page is hydrated and its live updates listen.
  onNuxtReady(() => {
    if (Date.now() - renderedAt.value > STALE_AFTER) resync()
  })
})
