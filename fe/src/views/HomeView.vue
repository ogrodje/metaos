<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { concerns, LONG_IN_PRODUCTION_DAYS, SOON_DAYS } from '@/attention'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ConcernRow from '@/components/ConcernRow.vue'
import EpisodeCard from '@/components/EpisodeCard.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import RecentActivity from '@/components/RecentActivity.vue'
import SessionCard from '@/components/SessionCard.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import StageBar from '@/components/StageBar.vue'
import {
  bySession, type Change, CHANGE_FIELDS, daysFrom, type Episode, EPISODE_FIELDS, episodePath, type EpisodeStatus,
  LIST_PICTURE_FIELDS, listPicture, relativeDate, type SessionGroup,
} from '@/episodes'
import { src, srcset } from '@/images'
import { errorMessage, graphql } from '@/graphql'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const released = ref<Episode[]>()
const releasedError = ref<string>()

// What's being made - for the team only. The episodes as they come, for what needs attention, and by recording
// session, for their lists.
const making = ref<{ upcoming: Episode[], inProduction: Episode[] }>()
const upcoming = ref<SessionGroup[]>()
const inProduction = ref<SessionGroup[]>()
const makingError = ref<string>()

// How many episodes are at each stage, and what the team did lately: the team's too
const stages = ref<{ status: EpisodeStatus, count: number }[]>()
const recent = ref<Change[]>()
const STAGES: EpisodeStatus[] = ['IDEA', 'PLANNED', 'SCHEDULED', 'RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS', 'PUBLISHED']

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

async function fetchReleased() {
  return (await graphql<{ releasedEpisodes: Episode[] }>(`{
    releasedEpisodes(limit: 8) { ${EPISODE_FIELDS} ${LIST_PICTURE_FIELDS} }
  }`)).releasedEpisodes
}

// The lists load again when the user signs in or out, as on the team they have more in them, e.g. docs, and quietly
// when MetaOS's data changes: what's shown stays until the new lists are there, and stays if they don't come. Only the
// latest load counts: an earlier one may answer after it.
let loads = 0

async function loadReleased(load: number, quietly: boolean) {
  try {
    const loaded = await fetchReleased()
    if (load !== loads) return
    released.value = keepUnchanged(released.value, loaded)
    releasedError.value = undefined
  } catch (error) {
    if (load !== loads) return
    if (quietly) throw error
    releasedError.value = errorMessage(error)
  }
}

async function loadMaking(load: number, quietly: boolean) {
  try {
    const data = await graphql<{
      upcomingRecordings: Episode[]
      inProduction: Episode[]
      episodes: { status: EpisodeStatus }[]
      ideas: { id: string }[]
      recentChanges: Change[]
    }>(`{
      upcomingRecordings { ${EPISODE_FIELDS} }
      inProduction { ${EPISODE_FIELDS} }
      episodes { status }
      ideas { id }
      recentChanges(limit: 60) { ${CHANGE_FIELDS} about { kind id name code } }
    }`)
    if (load !== loads) return
    making.value = keepUnchanged(making.value, { upcoming: data.upcomingRecordings, inProduction: data.inProduction })
    stages.value = keepUnchanged(stages.value, STAGES.map((status) => ({
      status, count: status === 'IDEA' ? data.ideas.length : data.episodes.filter((e) => e.status === status).length,
    })))
    recent.value = keepUnchanged(recent.value, data.recentChanges)
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
  return Promise.all([loadReleased(load, quietly), auth.team && loadMaking(load, quietly)])
}

// First the releases, as the server rendered them; what's being made, the team's, in the browser only, once it's known
// the user is on the team, which it already is when they came from another page
const first = await useFirstLoad('releases', fetchReleased)
released.value = first.data
releasedError.value = first.error

watch(() => auth.team, () => void loadLists())
onMounted(() => {
  if (auth.team) void loadMaking(++loads, false)
})
useLiveRefresh(() => loadLists(true))

usePageMeta(() => ({
  description: "MetaOS runs Ogrodje: the podcast's latest episodes, the people on them and where to listen.",
}))
</script>

<template>
  <main class="content">
    <h1 class="visually-hidden">Home</h1>

    <!-- Signed in, what's being made, and of it first what needs attention; at the side how many are at each stage, the
         releases and what the team did lately. Its place is kept while one who was signed in last time is checked. -->
    <div v-if="auth.likelyMember" class="home">
      <div class="main">
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

          <!-- What's being made, by recording session, each a card with its episodes a line each -->
          <section v-for="section in sections" :key="section.id" :aria-labelledby="section.id">
            <div class="heading">
              <h2 :id="section.id">{{ section.title }}</h2>
              <span class="count">{{ section.count }}</span>
            </div>
            <SessionCard v-for="group in section.groups" :key="group.key" :group="group" :overdue="overdue(section, group)"/>
          </section>
        </template>
      </div>

      <aside class="rail" aria-label="Lately">
        <section v-if="!makingError" aria-labelledby="by-stage">
          <div class="heading"><h2 id="by-stage">By stage</h2></div>
          <StageBar v-if="stages" :counts="stages"/>
          <LoadingRows v-else :rows="4" label="Loading how many episodes are at each stage"/>
        </section>

        <section aria-labelledby="latest-releases">
          <div class="heading">
            <h2 id="latest-releases">Latest releases</h2>
            <RouterLink class="more" to="/episodes">All episodes</RouterLink>
          </div>
          <LoadError v-if="releasedError" what="The latest releases" :error="releasedError" @retry="loadLists()"/>
          <LoadingRows v-else-if="!released" :rows="3" label="Loading the latest releases"/>
          <ul v-else class="releases">
            <li v-for="episode in released.slice(0, 3)" :key="episode.id">
              <RouterLink class="shot" :to="episodePath(episode)" tabindex="-1" aria-hidden="true">
                <img v-if="listPicture(episode)" :src="src(listPicture(episode)!, 320)" :srcset="srcset(listPicture(episode)!)" sizes="56px" alt="">
              </RouterLink>
              <div>
                <RouterLink class="title" :to="episodePath(episode)" lang="sl">{{ episode.title }}</RouterLink>
                <p class="meta"><span class="code">{{ episode.code }}</span><template v-if="episode.airedDate"> · {{ relativeDate(episode.airedDate) }}</template></p>
              </div>
            </li>
          </ul>
        </section>

        <section aria-labelledby="recent-activity">
          <div class="heading"><h2 id="recent-activity">Recent activity</h2></div>
          <RecentActivity v-if="recent" :changes="recent"/>
          <LoadingRows v-else-if="!makingError" :rows="4" label="Loading what the team did lately"/>
        </section>
      </aside>
    </div>

    <!-- Everyone else: the releases, as the server renders them, then for the team how to get at the rest -->
    <template v-else>
      <section aria-labelledby="latest-releases">
        <div class="heading">
          <h2 id="latest-releases">Latest releases</h2>
          <RouterLink class="more" to="/episodes">All episodes</RouterLink>
        </div>
        <LoadError v-if="releasedError" what="The latest releases" :error="releasedError" @retry="loadLists()"/>
        <LoadingRows v-else-if="!released" :rows="3" tall label="Loading the latest releases"/>
        <ul v-else class="episode-cards">
          <EpisodeCard v-for="episode in released" :key="episode.id" :episode="episode"/>
        </ul>
      </section>

      <SignInPrompt v-if="auth.ready && !auth.authenticated" compact title="On Ogrodje's team?">
        Sign in to see what's being recorded and produced, and what needs attention.
      </SignInPrompt>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';
@use '../assets/pictures';

// What's being made, and at the side the releases and what the team did lately
.home {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  column-gap: 56px;
  align-items: start;

  > .main, > .rail {
    display: flex;
    flex-direction: column;
    gap: 48px;
    min-width: 0;
  }

  > .rail {
    gap: 40px;
  }
}

.releases {
  padding: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 56px minmax(0, 1fr);
    gap: 12px;
    padding: 10px 0;
    border-top: 1px solid var(--rule);
  }

  .shot {
    display: block;
    aspect-ratio: 1;
    @include pictures.picture($small: true);

    img {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .title {
    display: block;
    font-size: 13.5px;
    line-height: 19px;
    font-weight: 600;
  }

  .meta {
    margin-top: 2px;
    font-size: 12px;
    line-height: 18px;
    color: var(--muted);
  }

  .code {
    font-family: var(--font-mono);
  }
}

.all-clear {
  max-width: 70ch;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
  color: var(--secondary);
}

// One column on narrower screens: what's being made, then the rest
@media (max-width: 960px) {
  .home {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 48px;
  }
}
</style>
