<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { concerns, LONG_IN_PRODUCTION_DAYS, SOON_DAYS } from '@/attention'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ConcernRow from '@/components/ConcernRow.vue'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import FileLinks from '@/components/FileLinks.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import {
  bySession, daysFrom, type Episode, EPISODE_FIELDS, formatDate, relativeDate, sessionPath, type SessionGroup,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const released = ref<Episode[]>()
const releasedError = ref<string>()

// What's being made - for signed-in users only. The episodes as they come, for what needs attention, and by recording
// session, for their lists.
const making = ref<{ upcoming: Episode[], inProduction: Episode[] }>()
const upcoming = ref<SessionGroup[]>()
const inProduction = ref<SessionGroup[]>()
const makingError = ref<string>()

// What needs attention, the most pressing first: a recording date that passed, then what's coming up without what it
// needs, then what's been in production long
const attention = computed(() => (making.value ? concerns(making.value.upcoming, making.value.inProduction) : []))

interface Section {
  id: string
  title: string
  groups: SessionGroup[]
  count: number
}

// What's coming up first, as it's what's due; only those with episodes in them, once they've loaded
const sections = computed(() =>
  [
    { id: 'upcoming-recordings', title: 'Upcoming recordings', groups: upcoming.value },
    { id: 'in-production', title: 'In production', groups: inProduction.value },
  ].flatMap(({ id, title, groups }): Section[] =>
    groups?.length ? [{ id, title, groups, count: groups.reduce((n, group) => n + group.episodes.length, 0) }] : []),
)

// A session's date that passed while it's still among the upcoming recordings
function overdue(section: Section, group: SessionGroup) {
  return section.id === 'upcoming-recordings' && !!group.session && daysFrom(new Date(), group.session.date) < 0
}

// The lists load again when the user signs in or out, as signed in they have more in them, e.g. docs, and quietly
// when MetaOS's data changes: what's shown stays until the new lists are there, and stays if they don't come. Only the
// latest load counts: an earlier one may answer after it.
let loads = 0

async function loadReleased(load: number, quietly: boolean) {
  try {
    const data = await graphql<{ releasedEpisodes: Episode[] }>(`{
      releasedEpisodes(limit: 3) { ${EPISODE_FIELDS} }
    }`)
    if (load !== loads) return
    released.value = keepUnchanged(released.value, data.releasedEpisodes)
    releasedError.value = undefined
  } catch (error) {
    if (load !== loads) return
    if (quietly) throw error
    releasedError.value = errorMessage(error)
  }
}

async function loadMaking(load: number, quietly: boolean) {
  try {
    const data = await graphql<{ upcomingRecordings: Episode[], inProduction: Episode[] }>(`{
      upcomingRecordings { ${EPISODE_FIELDS} }
      inProduction { ${EPISODE_FIELDS} }
    }`)
    if (load !== loads) return
    making.value = keepUnchanged(making.value, { upcoming: data.upcomingRecordings, inProduction: data.inProduction })
    upcoming.value = keepUnchanged(upcoming.value, bySession(data.upcomingRecordings))
    inProduction.value = keepUnchanged(inProduction.value, bySession(data.inProduction))
    makingError.value = undefined
  } catch (error) {
    if (load !== loads) return
    if (quietly) throw error
    makingError.value = errorMessage(error)
  }
}

function loadLists(quietly = false) {
  const load = ++loads
  if (!quietly) {
    making.value = undefined
    upcoming.value = undefined
    inProduction.value = undefined
    makingError.value = undefined
    releasedError.value = undefined
  }
  return Promise.all([loadReleased(load, quietly), auth.authenticated && loadMaking(load, quietly)])
}

watch(() => auth.authenticated, () => void loadLists(), { immediate: true })
useLiveRefresh(() => loadLists(true))
</script>

<template>
  <main class="content">
    <h1 class="visually-hidden">Home</h1>

    <!-- For visitors who aren't signed in: how to get at the rest -->
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to use MetaOS">
      Sign in with Discord or Google to see the episodes in production and the upcoming recordings. Or use MetaOS from
      your AI assistant over MCP: Usage shows how to connect it.
    </SignInPrompt>

    <!-- Signed in, what's being made comes first, and of it first what needs attention; the releases are last. Its
         place is kept while one who was signed in last time is checked, so the releases don't jump down. -->
    <template v-if="auth.likelyMember">
      <LoadError v-if="makingError" what="The upcoming recordings and what's in production" :error="makingError"
                 @retry="loadLists()"/>
      <section v-else-if="!making" aria-labelledby="needs-attention">
        <div class="heading"><h2 id="needs-attention">Needs attention</h2></div>
        <LoadingRows :rows="3" label="Loading what's being made"/>
      </section>

      <template v-else>
        <section aria-labelledby="needs-attention">
          <div class="heading">
            <h2 id="needs-attention">Needs attention</h2>
            <span class="count">{{ attention.length }}</span>
          </div>
          <ul v-if="attention.length" class="episodes">
            <ConcernRow v-for="concern in attention" :key="concern.episode.id" :concern="concern"/>
          </ul>
          <p v-else class="all-clear">
            Nothing right now. The recordings in the next {{ SOON_DAYS }} days have their guests and docs, and nothing
            recorded {{ LONG_IN_PRODUCTION_DAYS }} days ago or more is waiting to be released.
          </p>
        </section>

        <!-- What's being made, a line an episode, as the Episodes page lists what's in the works -->
        <section v-for="section in sections" :key="section.id" :aria-labelledby="section.id">
          <div class="heading">
            <h2 :id="section.id">{{ section.title }}</h2>
            <span class="count">{{ section.count }}</span>
          </div>
          <!-- Episodes without a session are listed as they are, without a heading -->
          <template v-for="group in section.groups" :key="group.key">
            <h3 v-if="group.session" class="subheading session">
              <RouterLink :to="sessionPath(group.session)">
                <time :datetime="group.session.date">{{ formatDate(group.session.date) }}</time>
                <span class="when" :class="{ overdue: overdue(section, group) }">{{ relativeDate(group.session.date) }}</span>
                <span v-if="group.session.location" class="location">{{ group.session.location }}</span>
              </RouterLink>
              <span class="files"><FileLinks :of="group.session"/></span>
            </h3>
            <ul class="episodes">
              <EpisodeRow v-for="episode in group.episodes" :key="episode.id" :episode="episode" under-session/>
            </ul>
          </template>
        </section>
      </template>
    </template>

    <section aria-labelledby="latest-releases">
      <div class="heading">
        <h2 id="latest-releases">Latest releases</h2>
        <span v-if="released" class="count">{{ released.length }}</span>
      </div>
      <LoadError v-if="releasedError" what="The latest releases" :error="releasedError" @retry="loadLists()"/>
      <LoadingRows v-else-if="!released" :rows="3" tall label="Loading the latest releases"/>
      <ul v-else class="episodes">
        <EpisodeItem v-for="episode in released" :key="episode.id" :episode="episode"/>
      </ul>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// The date, then where, which on phones goes on a line of its own when it doesn't fit
.session {
  a {
    display: flex;
    flex-wrap: wrap;
    column-gap: 8px;
  }

  .when, .location {
    font-weight: 400;
  }

  .when {
    color: var(--muted);
  }

  .location {
    color: var(--secondary);
  }

  // A recording date that passed: what needs attention says why
  .when.overdue {
    color: var(--accent-text);
  }

  .files {
    display: flex;
  }
}


.all-clear {
  max-width: 70ch;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
  color: var(--secondary);
}

</style>
