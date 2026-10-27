<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import FilterBar from '@/components/FilterBar.vue'
import IdeaRow from '@/components/IdeaRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import PageHeader from '@/components/PageHeader.vue'
import SelectFilter from '@/components/SelectFilter.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import { EPISODE_FIELDS, type Idea } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { IMAGE_FIELDS } from '@/images'
import { episodeText, matches, READINESS, readiness } from '@/lists'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// The episode ideas, the most recently added first; undefined while they load
const ideas = ref<Idea[]>()
const error = ref<string>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Ideas are the team's, so they load only for an approved user. Quietly when MetaOS's data changes: what's shown stays
// until the new ones are there, and stays if they don't come.
async function loadIdeas(quietly = false) {
  const load = ++loads
  if (!quietly) {
    ideas.value = undefined
    error.value = undefined
  }
  if (!auth.team) return
  try {
    const data = await graphql<{ ideas: Idea[] }>(`{ ideas { ${EPISODE_FIELDS} addedDate recommendedBy { id name avatar { ${IMAGE_FIELDS} } } } }`)
    if (load !== loads) return
    ideas.value = keepUnchanged(ideas.value, data.ideas)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// Again when one signs in
watch(() => auth.team, () => void loadIdeas(), { immediate: true })
useLiveRefresh(() => loadIdeas(true))

// Which, and how they're grouped, as the address says, e.g. ?show=Borderless&by=Andraž Sraka&group=recommender
const show = useQueryState('show', 'all')
const by = useQueryState('by', 'all')
const group = useQueryState<'readiness' | 'recommender' | 'none'>('group', 'readiness', ['readiness', 'recommender', 'none'])
const find = useQueryState('q', '')
const clear = useClearQuery()

const all = computed(() => ideas.value ?? [])
const filtering = computed(() => show.value !== 'all' || by.value !== 'all' || find.value.trim() !== '')

// Each value of a field with how many ideas have it, the most first, and those without one last
function counted(of: (idea: Idea) => string | null, none: string) {
  const counts = new Map<string, number>()
  for (const idea of all.value) counts.set(of(idea) ?? '', (counts.get(of(idea) ?? '') ?? 0) + 1)
  return [...counts].filter(([value]) => value).sort((a, b) => b[1] - a[1])
    .map(([value, count]) => ({ value, title: value, count }))
    .concat(counts.has('') ? [{ value: 'none', title: none, count: counts.get('')! }] : [])
}

const shows = computed(() => [{ value: 'all', title: 'Every show' }, ...counted((idea) => idea.show, 'No show')])
// By their whole names, in a select: there may be many, and two with the same first name
const recommenders = computed(() => [
  { value: 'all', title: 'Anyone' },
  ...counted((idea) => idea.recommendedBy?.name ?? null, 'Nobody'),
])

const shown = computed(() => all.value.filter((idea) =>
  (show.value === 'all' || (show.value === 'none' ? !idea.show : idea.show === show.value))
  && (by.value === 'all' || (by.value === 'none' ? !idea.recommendedBy : idea.recommendedBy?.name === by.value))
  && matches(`${episodeText(idea)} ${idea.recommendedBy?.name ?? ''}`, find.value)))

// By how far along they are, or who recommended them, or not at all; groups without any left out
const groups = computed(() => {
  if (group.value === 'readiness') {
    return READINESS.map(({ id, title }) => ({ key: id, title, ideas: shown.value.filter((idea) => readiness(idea) === id) }))
      .filter((g) => g.ideas.length)
  }
  if (group.value === 'recommender') {
    const names = [...new Set(shown.value.map((idea) => idea.recommendedBy?.name ?? null))]
    return names.map((name) => ({
      key: name ?? 'nobody', title: name ? `Recommended by ${name}` : 'Recommended by nobody',
      ideas: shown.value.filter((idea) => (idea.recommendedBy?.name ?? null) === name),
    })).sort((a, b) => b.ideas.length - a.ideas.length)
  }
  return [{ key: 'all', title: null, ideas: shown.value }]
})

const GROUPINGS = [
  { value: 'readiness' as const, title: 'How far along' },
  { value: 'recommender' as const, title: 'Who recommended' },
  { value: 'none' as const, title: 'Newest first' },
]
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to see the ideas">
      Episode ideas are for Ogrodje's team. Sign in with Discord or Google, or use MetaOS from your AI assistant over
      MCP: <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>

    <!-- Once planned, an idea has a code and is listed with the episodes instead -->
    <template v-else>
      <PageHeader title="Ideas" :count="ideas?.length" :shown="filtering && ideas ? shown.length : undefined">
        Episodes that might be. Once an idea is planned, it gets a code and moves to Episodes.
      </PageHeader>

      <!-- Signed in, but not approved yet, as the note under the header says -->
      <p v-if="auth.ready && !auth.team" class="note">The ideas are here once an admin has approved your account.</p>

      <template v-else>
        <FilterBar v-model:find="find" placeholder="Find by title, guest or recommender">
          <ChipGroup v-if="shows.length > 2" v-model="show" label="Show" :options="shows"/>
          <template #end>
            <SelectFilter v-if="recommenders.length > 2" v-model="by" label="Recommended by" :options="recommenders"/>
            <SelectFilter v-model="group" label="Group by" :options="GROUPINGS"/>
          </template>
        </FilterBar>

        <LoadError v-if="error" what="The ideas" :error="error" @retry="loadIdeas()"/>
        <LoadingRows v-else-if="!ideas" :rows="6" tall label="Loading the ideas"/>
        <p v-else-if="!ideas.length" class="note">No ideas yet.</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['show', 'by', 'q'])">No ideas match</NoMatch>
        <table v-else class="ideas">
          <thead>
            <tr>
              <th>Idea</th>
              <th class="by">Recommended by</th>
              <th class="date">Recording</th>
              <th class="date">Added</th>
              <th class="files"><span class="visually-hidden">Files</span></th>
            </tr>
          </thead>
          <tbody v-for="g in groups" :key="g.key">
            <tr v-if="g.title" class="group">
              <th colspan="5" scope="rowgroup">{{ g.title }} <span class="count">{{ g.ideas.length }}</span></th>
            </tr>
            <IdeaRow v-for="idea in g.ideas" :key="idea.id" :idea="idea"/>
          </tbody>
        </table>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// One table, a group of rows each, under the group's name
.ideas {
  width: 100%;
  border-collapse: collapse;

  thead th {
    padding: 0 16px 8px 0;
    font-size: 12px;
    line-height: 16px;
    font-weight: 600;
    color: var(--muted);
    text-align: left;
    white-space: nowrap;
  }

  .by {
    width: 200px;
  }

  .date {
    width: 120px;
  }

  .files {
    width: 72px;
  }

  .group th {
    padding: 24px 0 8px;
    font-size: 14px;
    line-height: 20px;
    font-weight: 600;
    text-align: left;

    .count {
      margin-left: 4px;
      font-weight: 400;
    }
  }

  tbody:first-of-type .group th {
    padding-top: 8px;
  }
}

// On phones, the rows as blocks, without the table's header
@media (max-width: 640px) {
  .ideas, .ideas tbody {
    display: block;
  }

  .ideas thead {
    display: none;
  }

  .ideas .group {
    display: block;

    th {
      display: block;
    }
  }
}

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
