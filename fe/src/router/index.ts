import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { setPageTitle } from '@/title'

declare module 'vue-router' {
  interface RouteMeta {
    // The page's name, for the browser's tab; pages about one thing name it after that once it's loaded
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      meta: { title: 'Home' },
      component: HomeView,
    },
    // Every episode by season, but the ideas; for anyone not signed in, the released ones
    {
      path: '/episodes',
      name: 'episodes',
      meta: { title: 'Episodes' },
      component: () => import('../views/EpisodesView.vue'),
    },
    // Everyone, with where they're online; for anyone not signed in, those on the released episodes
    {
      path: '/people',
      name: 'people',
      meta: { title: 'People' },
      component: () => import('../views/PeopleView.vue'),
    },
    // A person's page, with every episode they're on, behind sign-in
    {
      path: '/people/:id',
      name: 'person',
      meta: { title: 'Person' },
      component: () => import('../views/PersonView.vue'),
    },
    // Everything a search finds, behind sign-in: where the header's search leads for all of it
    {
      path: '/search',
      name: 'search',
      meta: { title: 'Search' },
      component: () => import('../views/SearchView.vue'),
    },
    // The episode ideas, behind sign-in
    {
      path: '/ideas',
      name: 'ideas',
      meta: { title: 'Ideas' },
      component: () => import('../views/IdeasView.vue'),
    },
    // An episode's and a session's page, behind sign-in; an episode by its code, or its ID while it has none
    {
      path: '/episodes/:ref',
      name: 'episode',
      meta: { title: 'Episode' },
      component: () => import('../views/EpisodeView.vue'),
    },
    {
      path: '/sessions/:id',
      name: 'session',
      meta: { title: 'Recording session' },
      component: () => import('../views/SessionView.vue'),
    },
    {
      path: '/usage',
      name: 'usage',
      meta: { title: 'API and MCP usage' },
      // Its own chunk, loaded when the page is first visited
      component: () => import('../views/UsageView.vue'),
    },
    // Anything else: there's no such page, which says where to go instead
    {
      path: '/:path(.*)*',
      name: 'not-found',
      meta: { title: 'Page not found' },
      component: () => import('../views/NotFoundView.vue'),
    },
  ],
})

// Named on going to another page, not when only the query changes, e.g. while a search is typed
router.afterEach((to, from, failure) => {
  if (!failure && (to.path !== from.path || from.matched.length === 0)) setPageTitle(to.meta.title)
})

export default router
