<script setup lang="ts">
import {computed, nextTick, ref, watch} from 'vue'
import {RouterLink, RouterView, useRoute, useRouter} from 'vue-router'
import {faDiscord, faGithub} from '@fortawesome/free-brands-svg-icons'
import {faHouse, faLightbulb, faMicrophone, faUserGroup} from '@fortawesome/free-solid-svg-icons'
import {FontAwesomeIcon} from '@fortawesome/vue-fontawesome'
import {keepUnchanged, useLiveRefresh} from '@/changes'
import SearchPalette from '@/components/SearchPalette.vue'
import UserMenu from '@/components/UserMenu.vue'
import {graphql} from '@/graphql'
import {useAuthStore} from '@/stores/auth'
import {pageName} from '@/title'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// Who MetaOS takes the signed-in user for, and their person, through their account: undefined until MetaOS has said,
// null when it isn't linked to anyone; and whether they may link accounts to people, which only one user may
const name = ref<string>()
const person = ref<{ id: string } | null>()
const canLinkAccounts = ref(false)

interface Me {
  username: string | null
  person: { id: string, name: string } | null
  canLinkAccounts: boolean
}

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Again when the user signs in or out, and quietly when MetaOS's data changes, e.g. as someone links their account to
// their person, when what's shown stays if it doesn't come
async function loadMe(quietly = false) {
  const load = ++loads
  if (!quietly) {
    name.value = auth.username
    person.value = undefined
    canLinkAccounts.value = false
  }
  if (!auth.authenticated) return
  try {
    const {me} = await graphql<{ me: Me | null }>('{ me { username person { id name } canLinkAccounts } }')
    if (load !== loads) return
    name.value = me?.person?.name ?? me?.username ?? auth.username
    person.value = keepUnchanged(person.value, me?.person ? {id: me.person.id} : null)
    canLinkAccounts.value = me?.canLinkAccounts === true
  } catch (error) {
    if (load !== loads) return
    if (quietly) throw error
    console.error('MetaOS does not know the signed-in user', error)
  }
}

watch(() => auth.authenticated, () => void loadMe(), {immediate: true})
useLiveRefresh(() => (auth.authenticated ? loadMe(true) : undefined))

// The pages, as tabs in the order they're used; the team's only for signed-in users, and while one who was signed in
// last time is checked, so they don't drop out and come back. A new section, e.g. Partners or Analytics, is another tab
// here. API and MCP usage, how to connect to MetaOS, is in the footer.
const pages = computed(() => [
  {path: '/', title: 'Home', icon: faHouse},
  {path: '/episodes', title: 'Episodes', icon: faMicrophone},
  {path: '/ideas', title: 'Ideas', icon: faLightbulb, team: true},
  {path: '/people', title: 'People', icon: faUserGroup},
].filter((page) => !page.team || auth.likelyMember))

// Going to another page, as a screen reader hears it: focus moves to the page, which starts there for the keyboard too,
// and its name is said once it's known. Not on the first page, nor when only the query changes, e.g. while a search
// is typed, nor when the page has put it somewhere itself, e.g. the search page in its field.
const content = ref<HTMLElement>()

router.afterEach((to, from, failure) => {
  if (failure || from.matched.length === 0 || to.path === from.path) return
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
        <SearchPalette v-if="auth.authenticated"/>
        <UserMenu :name="name" :person="person" :can-link-accounts="canLinkAccounts"/>
      </div>
    </div>

    <nav class="pages" aria-label="Main">
      <RouterLink v-for="page in pages" :key="page.path" :to="page.path" :class="{ current: current(page.path) }"
                  :aria-current="current(page.path) ? 'page' : undefined">
        <FontAwesomeIcon :icon="page.icon"/>{{ page.title }}
      </RouterLink>
    </nav>
  </header>

  <!-- Takes the height the header and footer leave, so the footer stays at the bottom of short pages -->
  <div id="page" ref="content" class="page" tabindex="-1">
    <RouterView/>
  </div>

  <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ pageName }}</p>

  <footer>
    <div class="wrapper">
      <span class="name">Ogrodje MetaOS</span>
      <!-- How to connect to MetaOS over MCP or GraphQL, then where Ogrodje is elsewhere -->
      <div class="links">
        <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink>
        <nav class="elsewhere" aria-label="Ogrodje elsewhere">
          <a class="icon-link" href="https://github.com/ogrodje/metaos" title="MetaOS on GitHub" aria-label="MetaOS on GitHub">
            <FontAwesomeIcon :icon="faGithub"/>
          </a>
          <a class="icon-link" href="https://discord.gg/xV2Xc4VX9W" title="Ogrodje on Discord" aria-label="Ogrodje on Discord">
            <FontAwesomeIcon :icon="faDiscord"/>
          </a>
        </nav>
      </div>
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

:global(#app) {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.page {
  flex: 1;

  // Focused by going to another page, not by the user: no ring
  &:focus {
    outline: none;
  }
}

// The header's container, without its line
// The name, and at the right the links, under it on the narrowest phones rather than broken up
footer {
  .wrapper {
    max-width: var(--content-width);
    min-height: 64px;
    margin: 0 auto;
    padding: 0 24px;
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    align-content: center;
    gap: 0 24px;
  }

  .name {
    font-size: 13px;
    line-height: 20px;
    color: var(--muted);
    white-space: nowrap;
  }

  .links {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
  }

  // Quiet as the name, in a hit area as tall as the icons' beside it
  .usage {
    display: inline-flex;
    align-items: center;
    min-height: var(--icon-link);
    padding: 0 8px;
    border-radius: 8px;
    font-size: 13px;
    line-height: 20px;
    color: var(--secondary);
    white-space: nowrap;

    &:hover {
      background: var(--hover);
      color: var(--ink);
    }

    &[aria-current='page'] {
      color: var(--ink);
    }
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
      padding: 8px 16px;
    }
  }
}

</style>
