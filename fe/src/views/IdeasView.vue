<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { I18nT } from 'vue-i18n'
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
import { graphql } from '@/graphql'
import { IMAGE_FIELDS } from '@/images'
import { episodeText, matches, READINESS, readiness, readinessTitle } from '@/lists'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'

const auth = useAuthStore()
const { t, path } = useLocale()

// The episode ideas, the most recently added first; undefined while they load
const ideas = ref<Idea[]>()
// What went wrong, said in the page's language as it's shown (LoadError)
const error = ref<unknown>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Ideas are the team's, so they load only for staff. Quietly when MetaOS's data changes: what's shown stays
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
    error.value = e
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

const shows = computed(() => [{ value: 'all', title: t('lists.everyShow') }, ...counted((idea) => idea.show, t('lists.noShow'))])
// By their whole names, in a select: there may be many, and two with the same first name
const recommenders = computed(() => [
  { value: 'all', title: t('ideas.anyone') },
  ...counted((idea) => idea.recommendedBy?.name ?? null, t('ideas.nobody')),
])

const shown = computed(() => all.value.filter((idea) =>
  (show.value === 'all' || (show.value === 'none' ? !idea.show : idea.show === show.value))
  && (by.value === 'all' || (by.value === 'none' ? !idea.recommendedBy : idea.recommendedBy?.name === by.value))
  && matches(`${episodeText(idea)} ${idea.recommendedBy?.name ?? ''}`, find.value)))

// By how far along they are, or who recommended them, or not at all; groups without any left out
const groups = computed(() => {
  if (group.value === 'readiness') {
    return READINESS.map((id) => ({ key: id, title: readinessTitle(id, t), ideas: shown.value.filter((idea) => readiness(idea) === id) }))
      .filter((g) => g.ideas.length)
  }
  if (group.value === 'recommender') {
    const names = [...new Set(shown.value.map((idea) => idea.recommendedBy?.name ?? null))]
    return names.map((name) => ({
      key: name ?? 'nobody', title: name ? t('ideas.recommendedByName', { name }) : t('ideas.recommendedByNobody'),
      ideas: shown.value.filter((idea) => (idea.recommendedBy?.name ?? null) === name),
    })).sort((a, b) => b.ideas.length - a.ideas.length)
  }
  return [{ key: 'all', title: null, ideas: shown.value }]
})

const GROUPINGS = computed(() => [
  { value: 'readiness' as const, title: t('ideas.grouping.readiness') },
  { value: 'recommender' as const, title: t('ideas.grouping.recommender') },
  { value: 'none' as const, title: t('ideas.grouping.none') },
])
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('ideas.signInTitle')">
      <I18nT keypath="ideas.signIn" scope="global">
        <template #usage><RouterLink class="usage" :to="path('/usage')">{{ t('app.usage') }}</RouterLink></template>
      </I18nT>
    </SignInPrompt>

    <!-- Once planned, an idea has a code and is listed with the episodes instead -->
    <template v-else>
      <PageHeader :title="t('pages.ideas')" :count="ideas?.length" :shown="filtering && ideas ? shown.length : undefined">
        {{ t('ideas.lede') }}
      </PageHeader>

      <!-- Signed in as a member, not staff -->
      <p v-if="auth.ready && !auth.team" class="note">{{ t('ideas.staffOnly') }}</p>

      <template v-else>
        <FilterBar v-model:find="find" :placeholder="t('ideas.find')">
          <ChipGroup v-if="shows.length > 2" v-model="show" :label="t('lists.show')" :options="shows"/>
          <template #end>
            <SelectFilter v-if="recommenders.length > 2" v-model="by" :label="t('ideas.recommender')" :options="recommenders"/>
            <SelectFilter v-model="group" :label="t('ideas.groupBy')" :options="GROUPINGS"/>
          </template>
        </FilterBar>

        <LoadError v-if="error" :what="t('ideas.what')" :error="error" @retry="loadIdeas()"/>
        <LoadingRows v-else-if="!ideas" :rows="6" tall :label="t('ideas.loading')"/>
        <p v-else-if="!ideas.length" class="note">{{ t('ideas.none') }}</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['show', 'by', 'q'])">{{ t('ideas.noMatch') }}</NoMatch>
        <!-- A section a group, as the Episodes page's seasons; newest first, one without a heading -->
        <template v-else>
          <section v-for="g in groups" :key="g.key" :aria-labelledby="g.title ? `group-${g.key}` : undefined"
                   :aria-label="g.title ? undefined : t('pages.ideas')">
            <div v-if="g.title" class="heading">
              <h2 :id="`group-${g.key}`">{{ g.title }}</h2>
              <span class="count">{{ g.ideas.length }}</span>
            </div>
            <ul class="ideas">
              <IdeaRow v-for="idea in g.ideas" :key="idea.id" :idea="idea"/>
            </ul>
          </section>
        </template>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.ideas {
  padding: 0;
  list-style: none;
}

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
