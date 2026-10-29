<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import EpisodeCard from '@/components/EpisodeCard.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterChip from '@/components/FilterChip.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import PageHeader from '@/components/PageHeader.vue'
import ViewToggle, { type ListView } from '@/components/ViewToggle.vue'
import { type Episode, EPISODE_FIELDS, type EpisodeStatus, LIST_PICTURE_FIELDS, statusName } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { byStage, episodeText, IN_THE_WORKS, matches, seasonOf, seasonTitle, SET_ASIDE, STAGES } from '@/lists'
import { useClearQuery, useQueryState } from '@/query'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// Every episode for the team; for everyone else only the released ones
const episodes = ref<Episode[]>()
const error = ref<string>()

// How they're shown and which, as the address says, e.g. ?view=grid&show=Bytes&season=4&q=vres; and the stage, which
// the home page's stages lead to, e.g. ?status=RECORDED
const view = useQueryState<ListView>('view', 'list', ['list', 'grid'])
const show = useQueryState('show', 'all')
const season = useQueryState('season', 'all')
const status = useQueryState<EpisodeStatus | 'all'>('status', 'all', ['all', ...STAGES])
const find = useQueryState('q', '')
const clear = useClearQuery()

const all = computed(() => episodes.value ?? [])
const filtering = computed(() =>
  show.value !== 'all' || season.value !== 'all' || status.value !== 'all' || find.value.trim() !== '')

function clearFilters() {
  clear(['show', 'season', 'status', 'q'])
}

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
  && (status.value === 'all' || episode.status === status.value)
  && matches(episodeText(episode), find.value)))

// Still to be recorded, recorded, or being made, the team's, first: by stage, in the order they go through them, and
// at the end, folded away unless they're looked for, those on hold or cancelled
const stages = computed(() => byStage(shown.value))
const working = computed(() => stages.value.filter((stage) => IN_THE_WORKS.includes(stage.status)))
const workingCount = computed(() => stages.value.reduce((n, stage) => n + stage.episodes.length, 0))
const setAside = computed(() => stages.value.filter((stage) => SET_ASIDE.includes(stage.status)).flatMap((stage) => stage.episodes))
const setAsideOpen = ref(false)
const setAsideFolded = computed(() => !setAsideOpen.value && !filtering.value)

interface Season {
  id: string
  title: string
  episodes: Episode[]
  // Older seasons show their first row until they're opened, or the list is filtered
  foldable: boolean
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
  return [...groups].map(([id, episodes], index): Season => {
    const foldable = index >= 2 && !filtering.value && episodes.length > (view.value === 'grid' ? 4 : 5)
    return { id, episodes, title: seasonTitle(seasonOf(episodes[0]!)), foldable, folded: foldable && !opened.value.has(id) }
  })
})

// A folded season's first row: four in a grid, five in a list
const firstOf = (group: Season) => (group.folded ? group.episodes.slice(0, view.value === 'grid' ? 4 : 5) : group.episodes)

function toggle(group: Season) {
  const ids = new Set(opened.value)
  if (ids.has(group.id)) ids.delete(group.id)
  else ids.add(group.id)
  opened.value = ids
}

async function fetchEpisodes() {
  return (await graphql<{ episodes: Episode[] }>(`{ episodes { ${EPISODE_FIELDS} ${LIST_PICTURE_FIELDS} } }`)).episodes
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
    <PageHeader title="Episodes" :count="episodes?.length" :shown="filtering && episodes ? shown.length : undefined">
      <template v-if="auth.team">What's being made, by stage, then what's released, by season.</template>
      <template v-else-if="auth.ready && !auth.authenticated">
        Every released episode of Ogrodje's podcast. Signed in, you also see what's being recorded and produced.
      </template>
      <template v-else>Every released episode of Ogrodje's podcast, by season.</template>
    </PageHeader>

    <FilterBar v-model:find="find" placeholder="Find by title, guest or code">
      <FilterChip v-if="status !== 'all'" label="Stage" :value="statusName(status)" @clear="status = 'all'"/>
      <ChipGroup v-if="shows.length > 2" v-model="show" label="Show" :options="shows"/>
      <ChipGroup v-model="season" label="Season" :options="seasons"/>
      <template #end><ViewToggle v-model="view"/></template>
    </FilterBar>

    <LoadError v-if="error" what="The episodes" :error="error" @retry="loadEpisodes()"/>
    <LoadingRows v-else-if="!episodes" :rows="8" label="Loading the episodes"/>
    <NoMatch v-else-if="!shown.length" :find="find" @clear="clearFilters">No episodes match</NoMatch>

    <template v-else>
      <!-- The team's: what's being made, by stage, a line each -->
      <section v-if="stages.length" aria-labelledby="in-the-works">
        <div class="heading">
          <h2 id="in-the-works">In the works</h2>
          <span class="count">{{ workingCount }}</span>
        </div>
        <template v-for="stage in working" :key="stage.status">
          <h3 class="subheading">{{ statusName(stage.status) }} <span class="count">{{ stage.episodes.length }}</span></h3>
          <ul class="episodes">
            <EpisodeRow v-for="episode in stage.episodes" :key="episode.id" :episode="episode" under-stage/>
          </ul>
        </template>
        <template v-if="setAside.length">
          <div class="subheading">
            <h3 id="set-aside">On hold or cancelled <span class="count">{{ setAside.length }}</span></h3>
            <button v-if="!filtering" type="button" class="text-button more" :aria-expanded="!setAsideFolded" aria-describedby="set-aside"
                    @click="setAsideOpen = !setAsideOpen">{{ setAsideFolded ? `Show all ${setAside.length}` : 'Fold' }}</button>
          </div>
          <ul v-if="!setAsideFolded" class="episodes">
            <EpisodeRow v-for="episode in setAside" :key="episode.id" :episode="episode"/>
          </ul>
        </template>
      </section>

      <section v-for="group in released" :key="group.id" :aria-labelledby="group.id">
        <div class="heading">
          <h2 :id="group.id">{{ group.title }}</h2>
          <span class="count">{{ group.episodes.length }}</span>
          <button v-if="group.foldable" type="button" class="text-button more" :aria-expanded="!group.folded" :aria-describedby="group.id"
                  @click="toggle(group)">{{ group.folded ? `Show all ${group.episodes.length}` : 'Fold' }}</button>
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
