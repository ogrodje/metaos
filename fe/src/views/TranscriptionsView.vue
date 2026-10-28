<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import FilterBar from '@/components/FilterBar.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import PageHeader from '@/components/PageHeader.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import TranscribeForm from '@/components/TranscribeForm.vue'
import TranscriptionRow from '@/components/TranscriptionRow.vue'
import { errorMessage, graphql } from '@/graphql'
import { matches } from '@/lists'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'
import {
  isOn, providerName, type SpeechProvider, split, type Transcription, transcriptionText, TRANSCRIPTIONS,
} from '@/transcriptions'

const auth = useAuthStore()

// The latest transcriptions, and the providers to transcribe with; undefined while they load
const transcriptions = ref<Transcription[]>()
const providers = ref<SpeechProvider[]>([])
const error = ref<string>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// The team's, so they load only for an approved user. Quietly as they change (live updates, which their rows send as
// they go): what's shown stays until the new ones are there, and stays if they don't come.
async function load(quietly = false) {
  const load = ++loads
  if (!quietly) {
    transcriptions.value = undefined
    error.value = undefined
  }
  if (!auth.team) return
  try {
    const data = await graphql<{ transcriptions: Transcription[], speechProviders: SpeechProvider[] }>(TRANSCRIPTIONS)
    if (load !== loads) return
    transcriptions.value = keepUnchanged(transcriptions.value, data.transcriptions)
    providers.value = keepUnchanged(providers.value, data.speechProviders)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

watch(() => auth.team, () => void load(), { immediate: true })
useLiveRefresh(() => load(true))

// The page's clock, by which those on say how long they've taken so far
const now = ref(new Date())
let ticking: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  ticking = setInterval(() => (now.value = new Date()), 5000)
})
onBeforeUnmount(() => clearInterval(ticking))

// Which, as the address says, e.g. ?status=failed&provider=soniox
type StatusFilter = 'all' | 'on' | 'done' | 'failed' | 'cancelled'
const status = useQueryState<StatusFilter>('status', 'all', ['all', 'on', 'done', 'failed', 'cancelled'])
const provider = useQueryState('provider', 'all')
const find = useQueryState('q', '')
const clear = useClearQuery()

const all = computed(() => transcriptions.value ?? [])
const filtering = computed(() => status.value !== 'all' || provider.value !== 'all' || find.value.trim() !== '')

function inStatus(t: Transcription, which: StatusFilter) {
  if (which === 'all') return true
  if (which === 'on') return isOn(t)
  return t.status === which.toUpperCase()
}

const STATUSES: { value: StatusFilter, title: string }[] = [
  { value: 'all', title: 'All' },
  { value: 'on', title: 'On' },
  { value: 'done', title: 'Done' },
  { value: 'failed', title: 'Failed' },
  { value: 'cancelled', title: 'Cancelled' },
]
const statuses = computed(() => STATUSES.map((s) => ({ ...s, count: all.value.filter((t) => inStatus(t, s.value)).length }))
  .filter((s) => s.value === 'all' || s.count || s.value === status.value))

// The providers that have transcribed something, and those MetaOS has
const providerOptions = computed(() => {
  const names = [...new Set([...providers.value.map((p) => p.name), ...all.value.map((t) => t.provider)])].sort()
  return [
    { value: 'all', title: 'Any' },
    ...names.map((name) => ({ value: name, title: providerName(name), count: all.value.filter((t) => t.provider === name).length })),
  ]
})

const shown = computed(() => all.value.filter((t) =>
  inStatus(t, status.value) && (provider.value === 'all' || t.provider === provider.value) && matches(transcriptionText(t), find.value)))
const parts = computed(() => split(shown.value))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to see the transcriptions">
      Transcriptions are for Ogrodje's team. Sign in with Discord or Google, or use MetaOS from your AI assistant over
      MCP: <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>

    <template v-else>
      <PageHeader title="Transcriptions" :count="transcriptions?.length" :shown="filtering && transcriptions ? shown.length : undefined">
        What's said in episodes' video and audio, by Google's Speech-to-Text or Soniox. Each provider hears a few files at
        once; the rest wait their turn.
      </PageHeader>

      <!-- Signed in, but not approved yet, as the note under the header says -->
      <p v-if="auth.ready && !auth.team" class="note">The transcriptions are here once an admin has approved your account.</p>

      <template v-else>
        <TranscribeForm v-if="providers.length" :providers="providers" @started="load(true)"/>

        <FilterBar v-model:find="find" placeholder="Find by episode or file">
          <ChipGroup v-model="status" label="Status" :options="statuses"/>
          <template #end>
            <ChipGroup v-if="providerOptions.length > 2" v-model="provider" label="Provider" :options="providerOptions"/>
          </template>
        </FilterBar>

        <LoadError v-if="error" what="The transcriptions" :error="error" @retry="load()"/>
        <LoadingRows v-else-if="!transcriptions" :rows="6" label="Loading the transcriptions"/>
        <p v-else-if="!transcriptions.length" class="note">Nothing's been transcribed yet.</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['status', 'provider', 'q'])">No transcriptions match</NoMatch>
        <div v-else class="parts">
          <section v-if="parts.on.length" aria-labelledby="on-heading">
            <h2 id="on-heading" class="subheading">On now <span class="count">{{ parts.on.length }}</span></h2>
            <ul class="list">
              <TranscriptionRow v-for="t in parts.on" :key="t.id" :transcription="t" :now="now" @changed="load(true)"/>
            </ul>
          </section>
          <section v-if="parts.ended.length" aria-labelledby="ended-heading">
            <h2 id="ended-heading" class="subheading">Earlier <span class="count">{{ parts.ended.length }}</span></h2>
            <ul class="list">
              <TranscriptionRow v-for="t in parts.ended" :key="t.id" :transcription="t" :now="now" @changed="load(true)"/>
            </ul>
          </section>
        </div>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// What's on, then what ended, closer to each other than the page's sections
.parts {
  display: flex;
  flex-direction: column;
  gap: 32px;
}

.subheading {
  margin: 0 0 4px;
  font-size: 14px;
  line-height: 20px;
  font-weight: 600;

  .count {
    margin-left: 4px;
    font-weight: 400;
    color: var(--muted);
  }
}

.list {
  margin: 0;
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
