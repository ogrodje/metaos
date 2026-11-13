<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { I18nT } from 'vue-i18n'
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
import { graphql } from '@/graphql'
import { matches } from '@/lists'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'
import {
  isOn, providerName, type SpeechProvider, split, type Transcription, transcriptionText, TRANSCRIPTIONS,
} from '@/transcriptions'

const auth = useAuthStore()
const { t, path } = useLocale()

// The latest transcriptions, and the providers to transcribe with; undefined while they load
const transcriptions = ref<Transcription[]>()
const providers = ref<SpeechProvider[]>([])

// Whether the form to transcribe an episode is open; it closes once one's started, which the list then shows
const transcribing = ref(false)

function started() {
  transcribing.value = false
  void load(true)
}
const error = ref<unknown>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// The team's, so they load only for staff. Quietly as they change (live updates, which their rows send as
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
    error.value = e
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

function inStatus(transcription: Transcription, which: StatusFilter) {
  if (which === 'all') return true
  if (which === 'on') return isOn(transcription)
  return transcription.status === which.toUpperCase()
}

const statuses = computed(() => {
  const options: { value: StatusFilter, title: string }[] = [
    { value: 'all', title: t('transcriptions.filter.all') },
    { value: 'on', title: t('transcriptions.filter.on') },
    { value: 'done', title: t('transcriptions.filter.done') },
    { value: 'failed', title: t('transcriptions.filter.failed') },
    { value: 'cancelled', title: t('transcriptions.filter.cancelled') },
  ]
  return options.map((s) => ({ ...s, count: all.value.filter((each) => inStatus(each, s.value)).length }))
    .filter((s) => s.value === 'all' || s.count || s.value === status.value)
})

// The providers that have transcribed something, and those MetaOS has
const providerOptions = computed(() => {
  const names = [...new Set([...providers.value.map((p) => p.name), ...all.value.map((t) => t.provider)])].sort()
  return [
    { value: 'all', title: t('transcriptions.filter.anyProvider') },
    ...names.map((name) => ({ value: name, title: providerName(name), count: all.value.filter((each) => each.provider === name).length })),
  ]
})

const shown = computed(() => all.value.filter((each) =>
  inStatus(each, status.value) && (provider.value === 'all' || each.provider === provider.value)
  && matches(transcriptionText(each), find.value)))
const parts = computed(() => split(shown.value))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('transcriptions.signIn.title')">
      <I18nT keypath="transcriptions.signIn.text" scope="global">
        <template #usage><RouterLink class="usage" :to="path('/usage')">{{ t('pages.usage') }}</RouterLink></template>
      </I18nT>
    </SignInPrompt>

    <template v-else>
      <PageHeader :title="t('pages.transcriptions')" :count="transcriptions?.length"
                  :shown="filtering && transcriptions ? shown.length : undefined">
        {{ t('transcriptions.about') }}
        <template v-if="auth.team && providers.length" #actions>
          <button type="button" class="button" :class="{ primary: !transcribing }" :aria-expanded="transcribing"
                  aria-controls="transcribe" @click="transcribing = !transcribing">
            {{ transcribing ? t('common.cancel') : t('transcriptions.transcribeEpisode') }}
          </button>
        </template>
      </PageHeader>

      <!-- Signed in as a member, not staff -->
      <p v-if="auth.ready && !auth.team" class="note">{{ t('transcriptions.teamOnly') }}</p>

      <template v-else>
        <!-- Opened from the heading, as the page is a list like the others first -->
        <TranscribeForm v-if="transcribing && providers.length" id="transcribe" :providers="providers" @started="started"/>

        <FilterBar v-model:find="find" :placeholder="t('transcriptions.filter.find')">
          <ChipGroup v-model="status" :label="t('transcriptions.filter.status')" :options="statuses"/>
          <template #end>
            <ChipGroup v-if="providerOptions.length > 2" v-model="provider" :label="t('transcriptions.filter.provider')"
                       :options="providerOptions"/>
          </template>
        </FilterBar>

        <LoadError v-if="error" :what="t('transcriptions.what')" :error="error" @retry="load()"/>
        <LoadingRows v-else-if="!transcriptions" :rows="6" :label="t('transcriptions.loading')"/>
        <p v-else-if="!transcriptions.length" class="note">{{ t('transcriptions.none') }}</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['status', 'provider', 'q'])">
          {{ t('transcriptions.noMatch') }}
        </NoMatch>
        <div v-else class="parts">
          <section v-if="parts.on.length" aria-labelledby="on-heading">
            <div class="heading"><h2 id="on-heading">{{ t('transcriptions.onNow') }}</h2><span class="count">{{ parts.on.length }}</span></div>
            <ul class="list">
              <TranscriptionRow v-for="t in parts.on" :key="t.id" :transcription="t" :now="now" @changed="load(true)"/>
            </ul>
          </section>
          <section v-if="parts.ended.length" aria-labelledby="ended-heading">
            <div class="heading"><h2 id="ended-heading">{{ t('transcriptions.earlier') }}</h2><span class="count">{{ parts.ended.length }}</span></div>
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

// What's on, then what ended, as far apart as the page's sections
.parts {
  display: flex;
  flex-direction: column;
  gap: 56px;
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
