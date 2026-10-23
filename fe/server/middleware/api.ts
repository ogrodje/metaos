import { defineEventHandler, proxyRequest } from 'h3'
import { useRuntimeConfig } from 'nitropack/runtime'

// MetaOS's own paths, which the browser asks this origin for. In the cluster the Ingress sends them to MetaOS itself, so
// they never get here; in development, and when the build runs locally, this passes them on to MetaOS (metaosUrl), so
// the browser sees one origin there too.
const API = /^\/(graphql|events|koofr|dropbox)(\/|\?|$)/

export default defineEventHandler((event) => {
  if (!API.test(event.path)) return
  return proxyRequest(event, useRuntimeConfig(event).metaosUrl + event.path)
})
