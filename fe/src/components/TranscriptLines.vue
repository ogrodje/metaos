<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PersonPicker, { type Pickable } from '@/components/PersonPicker.vue'
import { errorMessage, graphql, UserError } from '@/graphql'
import { useLocale } from '@/useLocale'
import {
  NAME_SPEAKER, type SpeakerChoice, speakerChoices, speakerName, suggestedSpeaker, timestamp, TRANSCRIPT_LINES,
  type TranscriptLine, type TranscriptSpeaker,
} from '@/transcriptions'

// What a transcript says, a line a stretch, each from where it starts and, where the provider tells them apart, by
// whom; loaded once it's opened, as it's the whole episode. Its speakers are named above it: each as one of the
// episode's people (its host and guest offered first) or by a name, or cleared again
const props = defineProps<{ id: string, language: string }>()
const { t } = useLocale()

const lines = ref<TranscriptLine[]>()
const speakers = ref<TranscriptSpeaker[]>([])
const people = ref<SpeakerChoice[]>([])
const error = ref<unknown>()

async function load() {
  error.value = undefined
  try {
    const { transcript } = await graphql<{
      transcript: {
        lines: TranscriptLine[]
        speakers: TranscriptSpeaker[]
        episode: { people: SpeakerChoice[] } | null
      } | null
    }>(TRANSCRIPT_LINES, { id: props.id })
    if (!transcript) throw new UserError('transcriptions.lines.removed')
    speakers.value = transcript.speakers
    people.value = speakerChoices(transcript.episode?.people ?? [])
    lines.value = transcript.lines
  } catch (e) {
    error.value = e
  }
}

onMounted(load)

// Naming one: which, while it's sent, and why it wasn't
const naming = ref<string | null>(null)
const busy = ref(false)
const nameError = ref<string>()

const pickable = computed<Pickable[]>(() => people.value.map((p) => ({ id: p.personId, name: p.name })))

function suggestion(label: string) {
  return suggestedSpeaker(label, speakers.value, people.value)?.name ?? null
}

async function name(label: string, variables: { person?: string, name?: string }) {
  if (busy.value) return
  busy.value = true
  nameError.value = undefined
  try {
    const { transcript } = await graphql<{ transcript: { speakers: TranscriptSpeaker[] } }>(
      NAME_SPEAKER, { id: props.id, label, person: null, name: null, ...variables })
    speakers.value = transcript.speakers
    naming.value = null
  } catch (e) {
    nameError.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

function open(label: string) {
  nameError.value = undefined
  naming.value = label
}
</script>

<template>
  <LoadError v-if="error" :what="t('transcriptions.lines.what')" :error="error" @retry="load()"/>
  <LoadingRows v-else-if="!lines" :rows="4" :label="t('transcriptions.lines.loading')"/>
  <template v-else>
    <section v-if="speakers.length" class="speakers" :aria-label="t('transcriptions.speakerNames.heading')">
      <p class="hint">{{ t('transcriptions.speakerNames.hint') }}</p>
      <ul>
        <li v-for="speaker in speakers" :key="speaker.label">
          <div class="speaker">
            <span class="label">{{ t('transcriptions.speaker', { label: speaker.label }) }}</span>
            <span v-if="speaker.name" class="name">{{ speaker.name }}</span>
            <span v-else class="unnamed">{{ t('transcriptions.speakerNames.notNamed') }}</span>
            <template v-if="naming !== speaker.label">
              <button type="button" class="button small" :disabled="busy" @click="open(speaker.label)">
                {{ speaker.name ? t('transcriptions.speakerNames.rename') : t('transcriptions.speakerNames.name') }}
              </button>
              <button v-if="speaker.name" type="button" class="button small quiet" :disabled="busy"
                      @click="name(speaker.label, {})">
                {{ t('transcriptions.speakerNames.clear') }}
              </button>
            </template>
          </div>
          <PersonPicker v-if="naming === speaker.label" :people="pickable" :suggest="suggestion(speaker.label)"
                        :label="t('transcriptions.speakerNames.nameWho', { speaker: t('transcriptions.speaker', { label: speaker.label }) })"
                        :busy="busy" :busy-text="t('transcriptions.speakerNames.naming')" free
                        @pick="(person) => name(speaker.label, { person: person.id })"
                        @write="(written) => name(speaker.label, { name: written })"
                        @cancel="naming = null"/>
          <p v-if="naming === speaker.label && nameError" class="error" role="alert">{{ nameError }}</p>
        </li>
      </ul>
    </section>

    <p v-if="!lines.length" class="none">{{ t('transcriptions.lines.none') }}</p>
    <ol v-else class="lines" :lang="language">
      <li v-for="(line, index) in lines" :key="index">
        <span class="at">{{ timestamp(line.start) }}</span>
        <span class="said">
          <span v-if="line.speaker" class="who">{{ speakerName(line.speaker, speakers, t) }}</span>
          {{ line.text }}
        </span>
      </li>
    </ol>
  </template>
</template>

<style scoped lang="scss">
// Who's who, above what they said
.speakers {
  max-width: 680px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--rule);

  ul {
    display: grid;
    gap: 6px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
}

.speaker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;

  .label {
    min-width: 80px;
    color: var(--muted);
  }

  .name {
    font-weight: 600;
  }

  .unnamed {
    color: var(--muted);
    font-style: italic;
  }
}

.hint {
  margin: 0 0 8px;
  font-size: var(--text-small);
  color: var(--muted);
}

.error {
  margin: 4px 0 0;
  font-size: var(--text-small);
  color: var(--accent-text);
}

// Running text, at its measure, each line from where it starts
.lines {
  max-width: 680px;
  max-height: 480px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 64px 1fr;
    gap: 12px;
    padding: 4px 0;
    font-size: var(--text-body);
    line-height: 22px;
  }
}

.at {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.who {
  font-weight: 600;
  margin-right: 4px;
}

.none {
  font-size: var(--text-small);
  color: var(--muted);
}
</style>
