<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { faMagnifyingGlass } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import LoadError from '@/components/LoadError.vue'
import MarkedText from '@/components/MarkedText.vue'
import PersonItem from '@/components/PersonItem.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import { sessionPath, sessionPlace, sessionTitle } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { type EpisodeHit, episodeMatch, resultCount, SEARCH_QUERY, type SearchResults, searchWords } from '@/search'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'

// Everything a search finds, by kind, at /search?q=: what the palette's See all opens, and what a shared link to a
// search does. Typing searches as it pauses and puts the text in the address, so Back and a reload keep it.

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// All of them, as far as a page goes
const LIMIT = 100
const PAUSE = 200

type Tab = 'all' | 'episodes' | 'people' | 'sessions'

const queryText = () => (typeof route.query.q === 'string' ? route.query.q : '')

const text = ref(queryText())
const tab = ref<Tab>('all')
// The results with the text they're for, as what's typed is ahead of them
const found = ref<{ text: string, results: SearchResults }>()
const error = ref<string>()

const words = computed(() => (found.value ? searchWords(found.value.text) : []))
const results = computed(() => found.value?.results)

const tabs = computed(() => {
  const r = results.value
  if (!r) return []
  return [
    { id: 'all' as Tab, title: 'All', count: resultCount(r) },
    { id: 'episodes' as Tab, title: 'Episodes', count: r.episodeCount + (r.episode ? 1 : 0) },
    { id: 'people' as Tab, title: 'People', count: r.personCount },
    { id: 'sessions' as Tab, title: 'Sessions', count: r.sessionCount },
  ]
})

const shows = (kind: Tab) => tab.value === 'all' || tab.value === kind

// An episode as the other lists have it, a line while it's in the works and its summary once it's released, with why
// it was found when it doesn't show that
const match = (hit: EpisodeHit) =>
  episodeMatch(hit, words.value, hit.episode.status === 'PUBLISHED' ? { tinySummary: true, guests: true } : {})

// Typing goes into the address once it pauses, replacing it rather than adding a step to Back for each pause
let timer: ReturnType<typeof setTimeout> | undefined

watch(text, (value) => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    const q = value.trim()
    if (q !== queryText()) router.replace({ query: q ? { q } : {} })
  }, PAUSE)
})

onBeforeUnmount(() => clearTimeout(timer))

// Ready to type in, also when it's come to from the search's See all, with what was searched there
const input = ref<HTMLInputElement>()
onMounted(() => input.value?.focus())

usePageTitle(() => (queryText() ? `Search: ${queryText()}` : 'Search'))

// Only the latest answer counts
let asks = 0

// What's in the address, searched again quietly when MetaOS's data changes: what's found stays until the new results
// are there, and stays if they don't come
async function search(quietly = false) {
  const q = queryText().trim()
  const asked = ++asks
  if (!auth.authenticated || !q) {
    found.value = undefined
    error.value = undefined
    return
  }
  try {
    const data = await graphql<{ search: SearchResults }>(SEARCH_QUERY, { text: q, limit: LIMIT })
    if (asked !== asks) return
    found.value = keepUnchanged(found.value, { text: q, results: data.search })
    error.value = undefined
    // Another search starts with all of it, unless the kind shown has some
    if (!tabs.value.find((t) => t.id === tab.value)?.count) tab.value = 'all'
  } catch (e) {
    if (asked !== asks) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// As typed, and also on Back, a reload, a link, and when the user signs in
watch([() => route.query.q, () => auth.authenticated], () => {
  const q = queryText().trim()
  if (q !== text.value.trim()) text.value = q
  void search()
}, { immediate: true })
useLiveRefresh(() => search(true))
</script>

<template>
  <main class="content">
    <h1 class="visually-hidden">Search</h1>
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to search">
      Search finds Ogrodje's episodes, ideas too, the people on them and the recording sessions, which are for Ogrodje's
      team. Sign in with Discord or Google, or use MetaOS from your AI assistant over MCP:
      <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>

    <template v-else>
      <div class="top">
        <label class="field">
          <FontAwesomeIcon :icon="faMagnifyingGlass"/>
          <input ref="input" v-model="text" type="search" autocomplete="off" spellcheck="false" placeholder="A code, a name or a topic"
                 aria-label="Search">
        </label>
        <div v-if="results && resultCount(results)" class="tabs" role="group" aria-label="Show">
          <button v-for="t in tabs" v-show="t.count || t.id === 'all'" :key="t.id" type="button" :aria-pressed="tab === t.id"
                  @click="tab = t.id">
            {{ t.title }}<span class="count">{{ t.count }}</span>
          </button>
        </div>
      </div>

      <div v-if="!text.trim()" class="note">
        <p>Search the episodes, ideas too, the people on them and the recording sessions: by title, name, show, summary, or place.</p>
        <p>A code, <span class="code">S05E08</span>, <span class="code">s5e8</span> or <span class="code">5x08</span>, finds its episode; <span class="code">S04</span> lists a season.</p>
      </div>
      <LoadError v-else-if="error" what="The results" :error="error" @retry="search()"/>
      <p v-else-if="!found" class="note">Searching…</p>
      <div v-else-if="!resultCount(found.results)" class="note">
        <p>Nothing found for “{{ found.text }}”.</p>
        <p>Search looks in titles, names, shows, summaries and places. Try fewer words, or the start of one: jezikov finds jezikovni and jezikovnega.</p>
      </div>

      <template v-else-if="results">
        <section v-if="results.episode && shows('episodes')" aria-labelledby="episode">
          <div class="heading"><h2 id="episode">Episode {{ results.episode.code }}</h2></div>
          <ul class="episodes">
            <EpisodeItem v-if="results.episode.status === 'PUBLISHED'" :episode="results.episode"/>
            <EpisodeRow v-else :episode="results.episode"/>
          </ul>
        </section>

        <section v-if="results.episodes.length && shows('episodes')" aria-labelledby="episodes">
          <div class="heading">
            <h2 id="episodes">{{ results.season != null ? `Season ${results.season}` : 'Episodes' }}</h2>
            <span class="count">{{ results.episodeCount }}</span>
          </div>
          <ul class="episodes">
            <template v-for="hit in results.episodes" :key="hit.episode.id">
              <EpisodeItem v-if="hit.episode.status === 'PUBLISHED'" :episode="hit.episode" :match="match(hit)"/>
              <EpisodeRow v-else :episode="hit.episode" :match="match(hit)"/>
            </template>
          </ul>
          <p v-if="results.episodeCount > results.episodes.length" class="note more">
            The best {{ results.episodes.length }}; more words find fewer.
          </p>
        </section>

        <section v-if="results.people.length && shows('people')" aria-labelledby="people">
          <div class="heading">
            <h2 id="people">People</h2>
            <span class="count">{{ results.personCount }}</span>
          </div>
          <ul class="rows-plain">
            <PersonItem v-for="hit in results.people" :key="hit.person.id" :person="hit.person" :words="words"/>
          </ul>
        </section>

        <section v-if="results.sessions.length && shows('sessions')" aria-labelledby="sessions">
          <div class="heading">
            <h2 id="sessions">Recording sessions</h2>
            <span class="count">{{ results.sessionCount }}</span>
          </div>
          <ul class="rows">
            <li v-for="hit in results.sessions" :key="hit.session.id">
              <RouterLink :to="sessionPath(hit.session)"><MarkedText :text="sessionTitle(hit.session)" :words="words"/></RouterLink>
              <span v-if="sessionPlace(hit.session)" class="aside"><MarkedText :text="sessionPlace(hit.session)!" :words="words"/></span>
              <span v-if="hit.session.address" class="aside"><MarkedText :text="hit.session.address" :words="words"/></span>
              <span v-if="hit.session.episodes.length" class="aside">{{ hit.session.episodes.map((episode) => episode.code ?? episode.title).join(', ') }}</span>
            </li>
          </ul>
        </section>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}

.content {
  gap: 40px;
}

.top {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  padding: 0 16px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  color: var(--muted);

  &:focus-within {
    border-color: var(--hairline-strong);
  }

  input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: 0;
    background: none;
    font: inherit;
    font-size: var(--text-lead);
    line-height: 24px;
    color: var(--ink);

    &::placeholder {
      color: var(--muted);
    }
  }
}

// As the header's pages: the one shown underlined
.tabs {
  display: flex;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--hairline);

  button {
    padding: 8px 10px;
    border: 0;
    background: none;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
    cursor: pointer;

    &[aria-pressed='true'] {
      color: var(--ink);
      font-weight: 500;
      box-shadow: inset 0 -2px 0 var(--accent);
    }
  }

  .count {
    margin-left: 6px;
    font-variant-numeric: tabular-nums;
  }
}

.note p + p {
  margin-top: 4px;
}

.code {
  font-family: var(--font-mono);
}

.more {
  padding-top: 12px;
}

.rows-plain {
  padding: 0;
  list-style: none;
}

.rows a {
  font-weight: 500;
}

// Each after the one before it, with a dot between
.rows .aside + .aside::before {
  content: '·';
  content: '·' / '';
  margin-right: 8px;
}

</style>
