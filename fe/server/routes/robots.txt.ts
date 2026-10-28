import { defineEventHandler, setHeader } from 'h3'
import { useRuntimeConfig } from 'nitropack/runtime'

// What search engines may crawl: the pages anyone may see, which the sitemap lists, not the team's (nuxt.config.ts) nor
// MetaOS's API
export default defineEventHandler((event) => {
  const { siteUrl } = useRuntimeConfig(event).public
  setHeader(event, 'content-type', 'text/plain; charset=utf-8')
  return [
    'User-agent: *',
    'Disallow: /ideas',
    'Disallow: /transcriptions',
    'Disallow: /search',
    'Disallow: /sessions/',
    'Disallow: /graphql',
    'Disallow: /events',
    'Disallow: /mcp',
    'Disallow: /koofr/',
    'Disallow: /dropbox/',
    '',
    `Sitemap: ${siteUrl}/sitemap.xml`,
    '',
  ].join('\n')
})
