import type { RouteRecordRaw } from 'vue-router'
import HomeView from '../views/HomeView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    // The page's name, for the browser's tab, as a message key (locales/*.json, pages.*); pages about one thing name it
    // after that once it's loaded
    title?: string
  }
}

/** The languages, as @nuxtjs/i18n has them (nuxt.config.ts): Slovenian at the root, English under /en */
export const LOCALES = ['sl', 'en'] as const
export type Locale = (typeof LOCALES)[number]
export const DEFAULT_LOCALE: Locale = 'sl'

/**
 * The pages, in Slovenian, at their own paths. Which of them the server renders, and for how long they're kept, is by
 * their paths in nuxt.config.ts: the team's only in the browser.
 */
const pages: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    meta: { title: 'pages.home' },
    component: HomeView,
  },
  // Every episode by season, but the ideas; for anyone not signed in, the released ones
  {
    path: '/episodes',
    name: 'episodes',
    meta: { title: 'pages.episodes' },
    component: () => import('../views/EpisodesView.vue'),
  },
  // Everyone, with where they're online; for anyone not signed in, those on the released episodes
  {
    path: '/people',
    name: 'people',
    meta: { title: 'pages.people' },
    component: () => import('../views/PeopleView.vue'),
  },
  // A person's page, with every episode they're on; for anyone not signed in, those on the released episodes and only
  // those episodes
  {
    path: '/people/:id',
    name: 'person',
    meta: { title: 'pages.person' },
    component: () => import('../views/PersonView.vue'),
  },
  // Slovenia's tech, developer and startup events that haven't ended yet, from Goo, at ogrodje.si's old address of them
  {
    path: '/dogodki',
    name: 'events',
    meta: { title: 'pages.events' },
    component: () => import('../views/EventsView.vue'),
  },
  // Everything a search finds, behind sign-in: where the header's search leads for all of it
  {
    path: '/search',
    name: 'search',
    meta: { title: 'pages.search' },
    component: () => import('../views/SearchView.vue'),
  },
  // The episode ideas, behind sign-in
  {
    path: '/ideas',
    name: 'ideas',
    meta: { title: 'pages.ideas' },
    component: () => import('../views/IdeasView.vue'),
  },
  // Episodes' transcriptions, and the transcripts they made, behind sign-in
  {
    path: '/transcriptions',
    name: 'transcriptions',
    meta: { title: 'pages.transcriptions' },
    component: () => import('../views/TranscriptionsView.vue'),
  },
  // An episode's page, by its code, or its ID while it has none: for anyone not signed in, a released one's, without
  // what's the team's; a session's page, behind sign-in
  {
    path: '/episodes/:ref',
    name: 'episode',
    meta: { title: 'pages.episode' },
    component: () => import('../views/EpisodeView.vue'),
  },
  {
    path: '/sessions/:id',
    name: 'session',
    meta: { title: 'pages.session' },
    component: () => import('../views/SessionView.vue'),
  },
  {
    path: '/usage',
    name: 'usage',
    meta: { title: 'pages.usage' },
    // Its own chunk, loaded when the page is first visited
    component: () => import('../views/UsageView.vue'),
  },
  // The privacy policy, in Slovenian and English, at ogrodje.si's old address of it, which Google's sign-in consent
  // screen links to
  {
    path: '/zasebnost',
    name: 'privacy',
    meta: { title: 'pages.privacy' },
    component: () => import('../views/PrivacyView.vue'),
  },
  // Anything else: there's no such page, which says where to go instead
  {
    path: '/:path(.*)*',
    name: 'not-found',
    meta: { title: 'pages.notFound' },
    component: () => import('../views/NotFoundView.vue'),
  },
]

/**
 * Every page in each language, which Nuxt's router has instead of its own from files (router.options.ts): Slovenian at
 * its path, English under /en, named as @nuxtjs/i18n names them (`episode___sl`, `episode___en`), so its helpers
 * (useLocalePath, useSwitchLocalePath) and its locale from the route work as with pages it made itself. The address
 * alone says the language, as Cloudflare keeps pages by their address.
 */
export const routes: RouteRecordRaw[] = LOCALES.flatMap((locale) =>
  pages.map((page): RouteRecordRaw => ({
    ...page,
    name: `${String(page.name)}___${locale}`,
    path: locale === DEFAULT_LOCALE ? page.path : `/${locale}${page.path === '/' ? '' : page.path}`,
  }) as RouteRecordRaw),
)
