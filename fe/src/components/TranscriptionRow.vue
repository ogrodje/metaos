<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import TranscriptLines from '@/components/TranscriptLines.vue'
import { errorMessage, graphql } from '@/graphql'
import {
  CANCEL_TRANSCRIPTION, formatWhen, isOn, providerName, REMOVE_TRANSCRIPT, statusText, took, type Transcription,
  transcriptSummary,
} from '@/transcriptions'
import { useLocale } from '@/useLocale'

// One transcription: its episode and file, its provider, how far it is, and what may be done with it: cancelling one
// that's on; reading the transcript one made, or removing it, which is asked on its row first
// [now] is the page's clock, by which one that's on says how long it's taken so far
const props = defineProps<{ transcription: Transcription, now: Date }>()
const emit = defineEmits<{ changed: [] }>()
const { t, dateLocale, path } = useLocale()

const job = computed(() => props.transcription)
const on = computed(() => isOn(job.value))
const reading = ref(false)
const removing = ref(false)
const busy = ref(false)
const error = ref<string>()

async function act(query: string, variables: Record<string, unknown>) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await graphql(query, variables)
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

const cancel = () => act(CANCEL_TRANSCRIPTION, { id: job.value.id })

async function remove() {
  await act(REMOVE_TRANSCRIPT, { id: job.value.transcript?.id })
  if (!error.value) {
    removing.value = false
    reading.value = false
  }
}

const episodeLink = computed(() => (job.value.episode ? path(`/episodes/${job.value.episode.code ?? job.value.episode.id}`) : null))
// How long it's taken so far, or took
const duration = computed(() => took(job.value, props.now))
</script>

<template>
  <li class="transcription" :class="job.status.toLowerCase()">
    <!-- Its episode's code in a column of its own, as the Episodes page has it -->
    <span class="code" :aria-hidden="!job.episode?.code">{{ job.episode?.code ?? '—' }}</span>
    <div class="what">
      <RouterLink v-if="episodeLink && job.episode" :to="episodeLink" class="episode" lang="sl">{{ job.episode.title }}</RouterLink>
      <span v-else class="episode gone">{{ t('transcriptions.row.deletedEpisode') }}</span>
      <span class="file">{{ job.mediaName }}</span>
    </div>

    <div class="how">
      <span class="provider">{{ providerName(job.provider) }}<span v-if="job.batch"> · {{ t('transcriptions.row.batch') }}</span></span>
      <span class="status" :role="on ? 'status' : undefined">{{ statusText(job, t, dateLocale) }}</span>
      <span v-if="job.status === 'FAILED' && job.error" class="why">{{ job.error }}</span>
      <span v-else-if="job.transcript" class="summary">{{ transcriptSummary(job.transcript, t, dateLocale) }}</span>
      <span v-else-if="job.status === 'DONE'" class="summary">{{ t('transcriptions.row.transcriptGone') }}</span>
    </div>

    <div class="when">
      <span>{{ formatWhen(job.createdAt, dateLocale) }}<span v-if="job.requestedBy"> · {{ job.requestedBy }}</span></span>
      <span v-if="job.status !== 'CANCELLED' && duration" class="took">
        {{ on ? t('transcriptions.row.for', { duration }) : t('transcriptions.row.took', { duration }) }}
      </span>
    </div>

    <div class="actions">
      <button v-if="on" type="button" class="button small" :aria-disabled="busy || undefined" @click="cancel">
        {{ busy ? t('transcriptions.row.cancelling') : t('common.cancel') }}
      </button>
      <template v-else-if="job.transcript && !removing">
        <button type="button" class="button small" :aria-expanded="reading" @click="reading = !reading">
          {{ reading ? t('transcriptions.row.hide') : t('transcriptions.row.read') }}
        </button>
        <button type="button" class="button small quiet" @click="removing = true">{{ t('common.remove') }}</button>
      </template>
      <template v-else-if="job.transcript">
        <span class="ask">{{ t('transcriptions.row.removeAsk') }}</span>
        <button type="button" class="button small danger" :aria-disabled="busy || undefined" @click="remove">
          {{ busy ? t('transcriptions.row.removing') : t('common.remove') }}
        </button>
        <button type="button" class="button small" :disabled="busy" @click="removing = false">
          {{ t('transcriptions.row.keep') }}
        </button>
      </template>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="reading && job.transcript" class="transcript">
      <TranscriptLines :id="job.transcript.id" :language="job.language"/>
    </div>
  </li>
</template>

<style scoped lang="scss">
// Columns at the frame's width, the same in every row: its episode's code, what, how, when and what may be done (as wide
// as Read and Remove); on phones the code beside the rest, stacked. The code's column is as wide as the Episodes page's
// ($code-width in _episode.scss)
.transcription {
  display: grid;
  grid-template-columns: 72px minmax(0, 2fr) minmax(0, 2fr) minmax(0, 1.2fr) 176px;
  column-gap: 16px;
  row-gap: 4px;
  align-items: start;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
}

.what, .how, .when {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: var(--text-small);
  line-height: 20px;
}

.episode {
  font-size: var(--text-body);
  font-weight: 500;
  color: var(--ink);

  &:hover {
    color: var(--accent-text);
  }

  &.gone {
    color: var(--muted);
  }
}

.code {
  font-family: var(--font-mono);
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

.file, .provider, .when, .summary, .took {
  color: var(--muted);
  overflow-wrap: anywhere;
}

.status {
  font-weight: 500;
  color: var(--ink);
}

.failed .status, .why {
  color: var(--accent-text);
}

.cancelled .status {
  color: var(--muted);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.ask {
  font-size: var(--text-small);
  color: var(--secondary);
}

.button[aria-disabled='true'] {
  cursor: progress;
}

.error {
  grid-column: 1 / -1;
  margin: 0;
  font-size: var(--text-small);
  color: var(--accent-text);
}

.transcript {
  grid-column: 1 / -1;
  padding: 8px 0 4px;
}

@media (max-width: 640px) {
  .transcription {
    grid-template-columns: 60px minmax(0, 1fr);
  }

  .what, .how, .when, .actions {
    grid-column: 2;
  }

  .actions {
    justify-content: flex-start;
  }
}
</style>
