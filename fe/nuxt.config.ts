import { defineNuxtConfig } from 'nuxt/config'

// The front-end, rendered by Nuxt: pages anyone may see on the server, as someone who isn't signed in sees them, and
// then in the browser, which signs the user in and loads what's theirs; the team's pages only in the browser.

// Pages anyone may see: rendered as someone who isn't signed in sees them, so the same for everyone, and kept for a
// minute by Nitro and Cloudflare, then served while they're rendered again for up to ten
const PUBLIC = { cache: { swr: true, maxAge: 60, staleMaxAge: 600 } }

// The team's pages: about who's asking, so rendered in the browser only, and never kept or indexed
const TEAM = { ssr: false, headers: { 'cache-control': 'no-store', 'x-robots-tag': 'noindex' } }

export default defineNuxtConfig({
  compatibilityDate: '2026-10-23',
  // The app keeps its own layout: src/App.vue, its views and components, and its routes (src/router.options.ts)
  srcDir: 'src',
  dir: { app: '.' },
  pages: true,
  // Imports stay explicit, as they are in the rest of the code: composables from '#imports', components by their files
  imports: { autoImport: false },
  components: { dirs: [] },
  modules: ['@pinia/nuxt'],
  // Inter, served with the app rather than from Google; Font Awesome's CSS comes with it too, rather than being added
  // at runtime (plugins/fontawesome.ts), so icons don't show unstyled first
  css: ['@fontsource-variable/inter', '~/assets/main.scss', '@fortawesome/fontawesome-svg-core/styles.css'],
  app: {
    head: {
      htmlAttrs: { lang: 'en' },
      link: [{ rel: 'icon', type: 'image/png', href: '/favicon.png' }],
      meta: [{ property: 'og:site_name', content: 'Ogrodje MetaOS' }],
    },
  },
  routeRules: {
    '/': PUBLIC,
    '/episodes': PUBLIC,
    '/episodes/**': PUBLIC,
    '/people': PUBLIC,
    '/people/**': PUBLIC,
    '/usage': PUBLIC,
    '/ideas': TEAM,
    '/search': TEAM,
    '/sessions/**': TEAM,
    '/sitemap.xml': { cache: { swr: true, maxAge: 3600 } },
    // keycloak-js asks Keycloak whether the user is signed in through a hidden iframe of silent-check-sso.html, so
    // MetaOS's pages may be framed - by MetaOS itself only
    '/**': { headers: { 'x-frame-options': 'SAMEORIGIN', 'x-content-type-options': 'nosniff' } },
  },
  // At runtime, from the environment, so one build works everywhere: NUXT_METAOS_URL, NUXT_PUBLIC_ISSUER, ...
  runtimeConfig: {
    // MetaOS's API, which the server renders pages from; in development the browser's requests to it go there too
    // (server/middleware/api.ts). In the cluster, its Service: http://metaos
    metaosUrl: 'http://localhost:8080',
    public: {
      // Where users sign in, and as which Keycloak client (stores/auth.ts)
      issuer: 'http://localhost:8180/realms/metaos',
      clientId: 'metaos-ui',
      // Where MCP clients connect, for the Usage page
      mcpUrl: 'http://localhost:8080/mcp',
      // The front-end's public address, for canonical URLs, link previews and the sitemap
      siteUrl: 'http://localhost:5173',
    },
  },
  // The data a page was rendered with stays in it, rather than in a _payload.json of its own, which Nitro and Cloudflare
  // would keep apart from it: the browser could get a page and data rendered at different times, which don't hydrate
  experimental: { payloadExtraction: false },
  telemetry: false,
  devtools: { enabled: true },
})
