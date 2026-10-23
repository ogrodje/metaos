<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import EpisodeCard from '@/components/EpisodeCard.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import FilterBar from '@/components/FilterBar.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PageHeader from '@/components/PageHeader.vue'
import ViewToggle, { type ListView } from '@/components/ViewToggle.vue'
import { type Episode, EPISODE_FIELDS, THUMBNAIL_FIELDS } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { episodeText, matches, seasonOf, seasonTitle } from '@/lists'
import { useQueryState } from '@/query'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// Every episode for the team; for everyone else only the released ones
const episodes = ref<Episode[]>()
const error = ref<string>()

// How they're shown and which, as the address says, e.g. ?view=grid&show=Bytes&season=4&q=vres
const view = useQueryState<ListView>('view', 'list', ['list', 'grid'])
const show = useQueryState('show', 'all')
const season = useQueryState('season', 'all')
const find = useQueryState('q', '')

const all = computed(() => episodes.value ?? [])
const filtering = computed(() => show.value !== 'all' || season.value !== 'all' || find.value.trim() !== '')

// The shows they're on, with how many each, in the order they first come
const shows = computed(() => {
  const counts = new Map<string, { count: number, color: string | null }>()
  for (const episode of all.value) {
    if (!episode.show) continue
    const seen = counts.get(episode.show)
    counts.set(episode.show, { count: (seen?.count ?? 0) + 1, color: episode.showColor })
  }
  return [
    { value: 'all', title: 'Every show', count: all.value.length },
    ...[...counts].map(([name, { count, color }]) => ({ value: name, title: name, count, color })),
  ]
})

const seasons = computed(() => {
  const numbers = [...new Set(all.value.map(seasonOf).filter((n): n is number => n !== null))]
  return [
    { value: 'all', title: 'Every season' },
    ...numbers.filter((n) => n !== 99).map((n) => ({ value: String(n), title: seasonTitle(n) })),
    ...(numbers.includes(99) ? [{ value: '99', title: seasonTitle(99) }] : []),
  ]
})

const shown = computed(() => all.value.filter((episode) =>
  (show.value === 'all' || episode.show === show.value)
  && (season.value === 'all' || String(seasonOf(episode)) === season.value)
  && matches(episodeText(episode), find.value)))

// Recorded, being made, or still to be recorded: the team's, first
const working = computed(() => shown.value.filter((episode) => episode.status !== 'PUBLISHED'))

interface Season {
  id: string
  title: string
  episodes: Episode[]
  // Older seasons show their first row until they're opened, or the list is filtered
  folded: boolean
}

// Seasons whose episodes are all shown, besides the two latest
const opened = ref(new Set<string>())

// The released ones by the season in their codes, as they come: the latest first, then the bonus season (S99)
const released = computed(() => {
  const groups = new Map<string, Episode[]>()
  for (const episode of shown.value.filter((e) => e.status === 'PUBLISHED')) {
    const id = `season-${seasonOf(episode) ?? 'none'}`
    groups.set(id, [...(groups.get(id) ?? []), episode])
  }
  return [...groups].map(([id, episodes], index): Season => ({
    id, episodes, title: seasonTitle(seasonOf(episodes[0]!)),
    folded: index >= 2 && !filtering.value && !opened.value.has(id),
  }))
})

// A folded season's first row: four in a grid, five in a list
const firstOf = (group: Season) => (group.folded ? group.episodes.slice(0, view.value === 'grid' ? 4 : 5) : group.episodes)

function open(group: Season) {
  opened.value = new Set([...opened.value, group.id])
}

async function fetchEpisodes() {
  return (await graphql<{ episodes: Episode[] }>(`{ episodes { ${EPISODE_FIELDS} ${THUMBNAIL_FIELDS} } }`)).episodes
}

// Loads again when the user signs in or out, as on the team there's more, and quietly when MetaOS's data changes, when
// what's shown stays if they don't come; only the latest load counts
let loads = 0

async function loadEpisodes(quietly = false) {
  const load = ++loads
  try {
    const loaded = await fetchEpisodes()
    if (load !== loads) return
    episodes.value = keepUnchanged(episodes.value, loaded)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// First the released ones, as the server rendered them
const first = await useFirstLoad('episodes', fetchEpisodes)
episodes.value = first.data
error.value = first.error

watch(() => auth.team, () => void loadEpisodes())
useLiveRefresh(() => loadEpisodes(true))

usePageMeta(() => ({ description: "Every released episode of Ogrodje's podcast, by season, with where to listen." }))
</script>

<template>
  <main class="content">
    <PageHeader title="Episodes" :count="episodes?.length">
      <template v-if="auth.ready && !auth.authenticated">
        Every released episode of Ogrodje's podcast. Signed in, you also see what's being recorded and produced.
      </template>
    </PageHeader>

    <FilterBar v-model:find="find" placeholder="Find by title, guest or code">
      <ChipGroup v-if="shows.length > 2" v-model="show" label="Show" :options="shows"/>
      <ChipGroup v-model="season" label="Season" :options="seasons"/>
      <template #end><ViewToggle v-model="view"/></template>
    </FilterBar>

    <LoadError v-if="error" what="The episodes" :error="error" @retry="loadEpisodes()"/>
    <LoadingRows v-else-if="!episodes" :rows="8" label="Loading the episodes"/>
    <p v-else-if="!shown.length" class="note">No episodes match.</p>

    <template v-else>
      <!-- The team's: what's being made, a line each -->
      <section v-if="working.length" aria-labelledby="in-the-works">
        <div class="heading">
          <h2 id="in-the-works">In the works</h2>
          <span class="count">{{ working.length }}</span>
        </div>
        <ul class="episodes">
          <EpisodeRow v-for="episode in working" :key="episode.id" :episode="episode"/>
        </ul>
      </section>

      <section v-for="group in released" :key="group.id" :aria-labelledby="group.id">
        <div class="heading">
          <h2 :id="group.id">{{ group.title }}</h2>
          <span class="count">{{ group.episodes.length }}</span>
          <button v-if="group.folded" type="button" class="more" @click="open(group)">Show all {{ group.episodes.length }}</button>
        </div>
        <ul v-if="view === 'grid'" class="episode-cards">
          <EpisodeCard v-for="episode in firstOf(group)" :key="episode.id" :episode="episode"/>
        </ul>
        <ul v-else class="episodes">
          <EpisodeRow v-for="episode in firstOf(group)" :key="episode.id" :episode="episode" thumbnail/>
        </ul>
      </section>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';
</style>
