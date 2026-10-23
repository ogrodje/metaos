<script setup lang="ts">
import {computed, nextTick, ref, watch} from 'vue'
import {RouterLink, useRoute, useRouter} from 'vue-router'
import {faDiscord, faGithub} from '@fortawesome/free-brands-svg-icons'
import {faHouse, faLightbulb, faMicrophone, faPlug, faUserGroup} from '@fortawesome/free-solid-svg-icons'
import {FontAwesomeIcon} from '@fortawesome/vue-fontawesome'
import {NuxtPage} from '#components'
import {useHead, useRuntimeConfig} from '#imports'
import SearchPalette from '@/components/SearchPalette.vue'
import UserMenu from '@/components/UserMenu.vue'
import {graphql} from '@/graphql'
import {useAuthStore} from '@/stores/auth'
import {announcePage, fullTitle, pageName} from '@/title'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// Every page: its title, by its route until it names itself (usePageTitle), and its one address, without the query, for
// search engines and link previews; a page about one thing, e.g. an episode, adds what it is (seo.ts)
const {siteUrl} = useRuntimeConfig().public
const address = computed(() => siteUrl + route.path)
useHead({
  titleTemplate: fullTitle,
  title: () => route.meta.title,
  link: [{rel: 'canonical', href: address}],
  meta: [
    {property: 'og:title', content: () => route.meta.title ?? 'Ogrodje MetaOS'},
    {property: 'og:url', content: address},
    {property: 'og:type', content: 'website'},
  ],
})

// Who MetaOS takes the signed-in user for, and their person, once one is linked to them by email
const name = ref<string>()
const person = ref<{ id: string } | null>(null)

interface Me {
  username: string | null
  person: { id: string, name: string } | null
}

watch(() => auth.authenticated, async (authenticated) => {
  name.value = auth.username
  person.value = null
  if (!authenticated) return
  try {
    const {me} = await graphql<{ me: Me | null }>('{ me { username person { id name } } }')
    name.value = me?.person?.name ?? me?.username ?? auth.username
    person.value = me?.person ?? null
  } catch (error) {
    console.error('MetaOS does not know the signed-in user', error)
  }
}, {immediate: true})

// The pages, as tabs in the order they're used; the team's only for signed-in users, and while one who was signed in
// last time is checked, so they don't drop out and come back. A new section, e.g. Partners or Analytics, is another tab
// here.
const pages = computed(() => [
  {path: '/', title: 'Home', icon: faHouse},
  {path: '/episodes', title: 'Episodes', icon: faMicrophone},
  {path: '/ideas', title: 'Ideas', icon: faLightbulb, team: true},
  {path: '/people', title: 'People', icon: faUserGroup},
  {path: '/usage', title: 'Usage', icon: faPlug},
].filter((page) => !page.team || auth.likelyMember))

// Going to another page, as a screen reader hears it: focus moves to the page, which starts there for the keyboard too,
// and its name is said once it's known. Not on the first page, nor when only the query changes, e.g. while a search
// is typed, nor when the page has put it somewhere itself, e.g. the search page in its field.
const content = ref<HTMLElement>()

router.afterEach((to, from, failure) => {
  if (failure || from.matched.length === 0 || to.path === from.path) return
  announcePage(to.meta.title)
  nextTick(() => {
    if (!content.value?.contains(document.activeElement)) content.value?.focus({preventScroll: true})
  })
})

// A page's tab is underlined on its own pages too: Episodes on an episode's, People on a person's
function current(path: string) {
  return path === '/' ? route.path === '/' : route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<template>
  <a class="skip" href="#page">Skip to content</a>

  <!-- The logo, and at the right search and you; under them the pages, as tabs -->
  <header>
    <div class="top">
      <RouterLink class="logo" to="/" aria-label="Ogrodje MetaOS, home"><img src="/favicon.png" alt=""/></RouterLink>
      <div v-if="auth.ready" class="actions">
        <!-- Search is the team's, as most of what it finds is -->
        <SearchPalette v-if="auth.team"/>
        <UserMenu :name="name" :person="person"/>
      </div>
    </div>

    <nav class="pages" aria-label="Main">
      <RouterLink v-for="page in pages" :key="page.path" :to="page.path" :class="{ current: current(page.path) }"
                  :aria-current="current(page.path) ? 'page' : undefined">
        <FontAwesomeIcon :icon="page.icon"/>{{ page.title }}
      </RouterLink>
    </nav>
  </header>

  <!-- Signed in, but not approved yet: why they see what everyone does -->
  <div v-if="auth.ready && auth.authenticated && !auth.team" class="approval" role="status">
    <p>You're signed in, but an admin hasn't approved your account yet. Until they do, you see what everyone sees.</p>
  </div>

  <!-- Takes the height the header and footer leave, so the footer stays at the bottom of short pages -->
  <div id="page" ref="content" class="page" tabindex="-1">
    <NuxtPage/>
  </div>

  <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ pageName }}</p>

  <footer>
    <div class="wrapper">
      <span class="name">Ogrodje MetaOS</span>
      <nav class="elsewhere" aria-label="Ogrodje elsewhere">
        <a class="icon-link" href="https://github.com/ogrodje/metaos" title="MetaOS on GitHub" aria-label="MetaOS on GitHub">
          <FontAwesomeIcon :icon="faGithub"/>
        </a>
        <a class="icon-link" href="https://discord.gg/xV2Xc4VX9W" title="Ogrodje on Discord" aria-label="Ogrodje on Discord">
          <FontAwesomeIcon :icon="faDiscord"/>
        </a>
      </nav>
    </div>
  </footer>
</template>

<style scoped lang="scss">

// For the keyboard: past the header's links, straight to the page. Shown only while it's focused.
.skip {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 30;
  padding: 10px 14px;
  border-radius: 6px;
  background: var(--ink);
  color: #FFFFFF;
  font-size: 13px;
  line-height: 20px;
  font-weight: 500;
  transform: translateY(-200%);

  &:focus-visible {
    transform: none;
    color: #FFFFFF;
  }
}

header {
  border-bottom: 1px solid var(--hairline);
}

// The page's column: the logo lines up with what's under it
.top {
  max-width: var(--content-width);
  height: 64px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.logo {
  display: flex;

  img {
    display: block;
    height: 34px;
  }
}

.actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

// Tabs whose first label, not its padding, lines up with the logo. When they don't fit, they scroll sideways and fade
// out at the edge.
.pages {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 0 14px;
  display: flex;
  overflow-x: auto;
  scrollbar-width: none;
  mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent);

  &::-webkit-scrollbar {
    display: none;
  }

  a {
    display: inline-flex;
    flex: none;
    align-items: center;
    gap: 8px;
    padding: 12px 10px;
    font-size: 14px;
    line-height: 20px;
    color: var(--secondary);
    white-space: nowrap;

    svg {
      font-size: 13px;
      color: var(--muted);
    }

    // Inside the tab, as the strip clips anything outside it
    &:focus-visible {
      outline-offset: -4px;
      border-radius: 8px;
    }

    &.current {
      color: var(--ink);
      font-weight: 500;
      box-shadow: inset 0 -2px 0 var(--accent);

      svg {
        color: var(--accent-text);
      }
    }
  }
}

// Phones: the page's narrower gutter, and the tabs closer together, without their icons
@media (max-width: 640px) {
  .top {
    height: 56px;
    padding: 0 16px;
  }

  .logo img {
    height: 30px;
  }

  .actions {
    gap: 8px;
  }

  .pages {
    padding: 0 8px;

    a {
      padding: 10px 8px;

      svg {
        display: none;
      }
    }
  }
}

:global(#__nuxt) {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

// Under the header, in the page's column, as quiet as a note
.approval {
  border-bottom: 1px solid var(--hairline);
  background: var(--hover);

  p {
    max-width: var(--content-width);
    margin: 0 auto;
    padding: 12px 24px;
    font-size: 13px;
    line-height: 20px;
    color: var(--secondary);
  }

  @media (max-width: 640px) {
    p {
      padding: 12px 16px;
    }
  }
}

.page {
  flex: 1;

  // Focused by going to another page, not by the user: no ring
  &:focus {
    outline: none;
  }
}

// The header's container, without its line
footer {
  .wrapper {
    max-width: var(--content-width);
    min-height: 64px;
    margin: 0 auto;
    padding: 0 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 24px;
  }

  .name {
    font-size: 13px;
    line-height: 20px;
    color: var(--muted);
  }

  .elsewhere {
    display: flex;
    gap: 4px;
    // The glyphs, not their hit areas, line up with the content's edge
    margin-right: var(--icon-link-inset);

    a {
      color: var(--secondary);
    }
  }

  @media (max-width: 640px) {
    .wrapper {
      padding: 0 16px;
    }
  }
}

</style>
