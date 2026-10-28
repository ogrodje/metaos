<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import TranscriptLines from '@/components/TranscriptLines.vue'
import { errorMessage, graphql } from '@/graphql'
import {
  CANCEL_TRANSCRIPTION, formatWhen, isOn, providerName, REMOVE_TRANSCRIPT, statusText, took, type Transcription,
  transcriptSummary,
} from '@/transcriptions'

// One transcription: its episode and file, its provider, how far it is, and what may be done with it: cancelling one
// that's on; reading the transcript one made, or removing it, which is asked on its row first
// [now] is the page's clock, by which one that's on says how long it's taken so far
const props = defineProps<{ transcription: Transcription, now: Date }>()
const emit = defineEmits<{ changed: [] }>()

const t = computed(() => props.transcription)
const on = computed(() => isOn(t.value))
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
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

const cancel = () => act(CANCEL_TRANSCRIPTION, { id: t.value.id })

async function remove() {
  await act(REMOVE_TRANSCRIPT, { id: t.value.transcript?.id })
  if (!error.value) {
    removing.value = false
    reading.value = false
  }
}

const episodeLink = computed(() => (t.value.episode ? `/episodes/${t.value.episode.code ?? t.value.episode.id}` : null))
</script>

<template>
  <li class="transcription" :class="t.status.toLowerCase()">
    <div class="what">
      <RouterLink v-if="episodeLink && t.episode" :to="episodeLink" class="episode">
        <span v-if="t.episode.code" class="code">{{ t.episode.code }}</span> {{ t.episode.title }}
      </RouterLink>
      <span v-else class="episode gone">A deleted episode</span>
      <span class="file">{{ t.mediaName }}</span>
    </div>

    <div class="how">
      <span class="provider">{{ providerName(t.provider) }}<span v-if="t.batch"> · batch</span></span>
      <span class="status" :role="on ? 'status' : undefined">{{ statusText(t) }}</span>
      <span v-if="t.status === 'FAILED' && t.error" class="why">{{ t.error }}</span>
      <span v-else-if="t.transcript" class="summary">{{ transcriptSummary(t.transcript) }}</span>
      <span v-else-if="t.status === 'DONE'" class="summary">Its transcript was removed or replaced</span>
    </div>

    <div class="when">
      <span>{{ formatWhen(t.createdAt) }}<span v-if="t.requestedBy"> · {{ t.requestedBy }}</span></span>
      <span v-if="t.status !== 'CANCELLED'" class="took">{{ on ? 'for' : 'took' }} {{ took(t, now) }}</span>
    </div>

    <div class="actions">
      <button v-if="on" type="button" class="button small" :aria-disabled="busy || undefined" @click="cancel">
        {{ busy ? 'Cancelling…' : 'Cancel' }}
      </button>
      <template v-else-if="t.transcript && !removing">
        <button type="button" class="button small" :aria-expanded="reading" @click="reading = !reading">
          {{ reading ? 'Hide' : 'Read' }}
        </button>
        <button type="button" class="button small quiet" @click="removing = true">Remove</button>
      </template>
      <template v-else-if="t.transcript">
        <span class="ask">Remove its transcript?</span>
        <button type="button" class="button small danger" :aria-disabled="busy || undefined" @click="remove">
          {{ busy ? 'Removing…' : 'Remove' }}
        </button>
        <button type="button" class="button small" :disabled="busy" @click="removing = false">Keep</button>
      </template>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="reading && t.transcript" class="transcript">
      <TranscriptLines :id="t.transcript.id" :language="t.language"/>
    </div>
  </li>
</template>

<style scoped lang="scss">
// Four columns at the frame's width, the same in every row: what, how, when and what may be done (as wide as Read and
// Remove); stacked on phones
.transcription {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 2fr) minmax(0, 1.2fr) 176px;
  gap: 4px 16px;
  align-items: start;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
}

.what, .how, .when {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 13px;
  line-height: 20px;
}

.episode {
  font-size: 14px;
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
  font-variant-numeric: tabular-nums;
  color: var(--secondary);
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
  font-size: 13px;
  color: var(--secondary);
}

.button.small {
  min-height: 32px;
  padding: 0 10px;
}

.button.quiet {
  color: var(--secondary);
}

.button.danger {
  color: #FFFFFF;
  background: var(--accent-text);
  border-color: var(--accent-text);

  &:hover {
    color: #FFFFFF;
    background: #A3244A;
    border-color: #A3244A;
  }
}

.button[aria-disabled='true'] {
  color: var(--muted);
  cursor: progress;
}

.error {
  grid-column: 1 / -1;
  margin: 0;
  font-size: 13px;
  color: var(--accent-text);
}

.transcript {
  grid-column: 1 / -1;
  padding: 8px 0 4px;
}

@media (max-width: 640px) {
  .transcription {
    grid-template-columns: 1fr;
  }

  .actions {
    justify-content: flex-start;
  }
}
</style>
