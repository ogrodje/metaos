<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import { type Episode, EPISODE_FIELDS } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// Every episode for the team; for everyone else only the released ones
const episodes = ref<Episode[]>()
const error = ref<string>()

// A season's episodes in the works, a line each, and those released, with their summaries and where to listen
interface Season {
  id: string
  title: string
  working: Episode[]
  released: Episode[]
}

// By the season in their codes (S05E08 is in season 5), as they come: the latest first, then the bonus season (S99),
// and those without a code, put on hold or cancelled before they had one, last. Ideas have a page of their own.
const seasons = computed(() => {
  const groups = new Map<string, Season>()
  for (const episode of episodes.value ?? []) {
    const season = episode.code?.match(/^S(\d+)E/i)?.[1]
    const id = season ? `season-${Number(season)}` : 'without-code'
    if (!groups.has(id)) groups.set(id, { id, title: season ? `Season ${Number(season)}` : 'Without a code', working: [], released: [] })
    const group = groups.get(id)!
    ;(episode.status === 'PUBLISHED' ? group.released : group.working).push(episode)
  }
  return [...groups.values()]
})

async function fetchEpisodes() {
  return (await graphql<{ episodes: Episode[] }>(`{ episodes { ${EPISODE_FIELDS} } }`)).episodes
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
    <h1 class="visually-hidden">Episodes</h1>
    <p v-if="auth.ready && !auth.authenticated" class="note">
      These are the released episodes. Signed in, you also see what's being recorded and produced.
    </p>

    <LoadError v-if="error" what="The episodes" :error="error" @retry="loadEpisodes()"/>
    <LoadingRows v-else-if="!episodes" :rows="8" label="Loading the episodes"/>
    <section v-for="season in seasons" v-else :key="season.id" :aria-labelledby="season.id">
      <div class="heading">
        <h2 :id="season.id">{{ season.title }}</h2>
        <span class="count">{{ season.working.length + season.released.length }}</span>
      </div>
      <!-- Headed only when a season has both -->
      <template v-if="season.working.length">
        <h3 v-if="season.released.length" class="subheading">
          In the works <span class="count">{{ season.working.length }}</span>
        </h3>
        <ul class="episodes">
          <EpisodeRow v-for="episode in season.working" :key="episode.id" :episode="episode"/>
        </ul>
      </template>
      <template v-if="season.released.length">
        <h3 v-if="season.working.length" class="subheading">
          Released <span class="count">{{ season.released.length }}</span>
        </h3>
        <ul class="episodes">
          <EpisodeItem v-for="episode in season.released" :key="episode.id" :episode="episode"/>
        </ul>
      </template>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';
</style>
