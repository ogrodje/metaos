import { createError, defineEventHandler, setHeader } from 'h3'
import { useRuntimeConfig } from 'nitropack/runtime'
import { episodePath } from '~/episodes'
import { personPath } from '~/people'

// Where search engines find the pages anyone may see: the lists, every released episode and everyone on them, as
// MetaOS's API gives them to someone who isn't signed in. Kept for an hour (nuxt.config.ts).

interface Listed {
  episodes: { id: string, code: string | null, airedDate: string | null }[]
  people: { id: string }[]
}

const LISTS = ['/', '/episodes', '/people', '/usage']

// Addresses are ours, but an & would still break the XML
function escape(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

export default defineEventHandler(async (event) => {
  const { metaosUrl, public: { siteUrl } } = useRuntimeConfig(event)
  const response = await fetch(`${metaosUrl}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ query: '{ episodes { id code airedDate } people { id } }' }),
    signal: AbortSignal.timeout(10_000),
  })
  const { data, errors } = response.ok ? await response.json() as { data?: Listed, errors?: unknown[] } : {}
  if (!data || errors?.length) throw createError({ statusCode: 502, statusMessage: "MetaOS didn't list its pages" })

  const urls = [
    ...LISTS.map((path) => ({ path, modified: null })),
    ...data.episodes.map((episode) => ({ path: episodePath(episode), modified: episode.airedDate })),
    ...data.people.map((person) => ({ path: personPath(person), modified: null })),
  ]
  setHeader(event, 'content-type', 'application/xml; charset=utf-8')
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...urls.map(({ path, modified }) =>
      `<url><loc>${escape(siteUrl + path)}</loc>${modified ? `<lastmod>${modified}</lastmod>` : ''}</url>`),
    '</urlset>',
  ].join('\n')
})
