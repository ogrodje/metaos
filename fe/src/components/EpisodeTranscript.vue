<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import { formatDate } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import type { MediaFile } from '@/media'
import {
  type EpisodeTranscript, firstTranscript, providerName, speakerName, timestamp, TRANSCRIPT_LINES, type TranscriptLine,
  type TranscriptSpeaker, type TranscriptTurn, turns, youtubeAt,
} from '@/transcriptions'

// An episode's transcript as a conversation reads: each speaker's turn from where it starts, which opens the episode on
// YouTube there when it's of the full audio or video; the first turns, then all of them once asked for. Its other
// transcripts, by another provider or of another file, to switch to. Loaded once it's shown, as it's the whole episode.
const props = defineProps<{
  // The latest first
  transcripts: EpisodeTranscript[]
  media: Pick<MediaFile, 'id' | 'kind'>[]
  youtubeUrl: string | null
}>()

// How many turns it shows until all are asked for
const FIRST = 12

const chosen = ref(firstTranscript(props.transcripts)?.id)
const transcript = computed(() => props.transcripts.find((t) => t.id === chosen.value) ?? firstTranscript(props.transcripts))

// Each transcript's turns, once loaded, kept while another is shown
const loaded = ref<Record<string, { turns: TranscriptTurn[], speakers: TranscriptSpeaker[] }>>({})
const error = ref<string>()
const whole = ref(false)

async function load(id: string) {
  error.value = undefined
  try {
    const { transcript: found } = await graphql<{ transcript: { lines: TranscriptLine[], speakers: TranscriptSpeaker[] } | null }>(
      TRANSCRIPT_LINES, { id })
    if (!found) throw new Error('It was removed')
    loaded.value = { ...loaded.value, [id]: { turns: turns(found.lines), speakers: found.speakers } }
  } catch (e) {
    error.value = errorMessage(e)
  }
}

watch(() => transcript.value?.id, (id) => {
  whole.value = false
  if (id && !loaded.value[id]) void load(id)
}, { immediate: true })

const shown = computed(() => (transcript.value ? loaded.value[transcript.value.id] : undefined))
const listed = computed(() => (shown.value && !whole.value ? shown.value.turns.slice(0, FIRST) : shown.value?.turns ?? []))

// Each by its provider, and its file too when they're of more than one
const files = computed(() => new Set(props.transcripts.map((t) => t.mediaId)).size)
const label = (t: EpisodeTranscript) => (files.value > 1 ? `${providerName(t.provider)} · ${t.mediaName}` : providerName(t.provider))

// The full audio's and video's times are YouTube's, a bonus's aren't
const youtube = computed(() => {
  const kind = props.media.find((file) => file.id === transcript.value?.mediaId)?.kind
  return props.youtubeUrl && (kind === 'FULL_AUDIO' || kind === 'FULL_VIDEO') ? props.youtubeUrl : null
})

// Who's speaking, the second and later speakers greyer, so turns read apart
const second = (speaker: string | null) => !!speaker && shown.value?.speakers[0]?.label !== speaker
const unnamed = computed(() => transcript.value?.speakers.some((speaker) => !speaker.name) ?? false)
</script>

<template>
  <div v-if="transcript" class="episode-transcript">
    <div v-if="transcripts.length > 1" class="switch" role="group" aria-label="Transcript">
      <button v-for="t in transcripts" :key="t.id" type="button" :aria-pressed="t.id === transcript.id" @click="chosen = t.id">
        {{ label(t) }}
      </button>
    </div>
    <p class="note">
      Heard by {{ providerName(transcript.provider) }} on {{ formatDate(transcript.createdAt) }} from {{ transcript.mediaName }},
      as a machine hears it: names may be misspelt<template v-if="unnamed">, and speakers aren't named yet</template>.
    </p>

    <LoadError v-if="error" what="The transcript" :error="error" @retry="load(transcript.id)"/>
    <LoadingRows v-else-if="!shown" :rows="4" label="Loading the transcript"/>
    <p v-else-if="!shown.turns.length" class="note">Nothing was heard.</p>
    <template v-else>
      <ol class="turns" :lang="transcript.language">
        <li v-for="(turn, i) in listed" :key="i" :class="{ second: second(turn.speaker) }">
          <p class="who" lang="en">
            <span v-if="turn.speaker" class="name">{{ speakerName(turn.speaker, shown.speakers) }}</span>
            <a v-if="youtube" class="at" :href="youtubeAt(youtube, turn.start)" target="_blank" rel="noopener"
               :title="`Open it on YouTube at ${timestamp(turn.start)}`">{{ timestamp(turn.start) }}</a>
            <span v-else class="at">{{ timestamp(turn.start) }}</span>
          </p>
          <p class="text">{{ turn.text }}</p>
        </li>
      </ol>
      <button v-if="shown.turns.length > FIRST" type="button" class="text-button" :aria-expanded="whole" @click="whole = !whole">
        {{ whole ? 'Only the start' : `Show all ${shown.turns.length} ${transcript.speakers.length ? 'turns' : 'lines'}` }}
      </button>
    </template>
  </div>
</template>

<style scoped lang="scss">
.episode-transcript {
  display: grid;
  justify-items: start;
  gap: 12px;
}

// Which transcript, as the pictures' tabs (EpisodePictures)
.switch {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 3px;
  background: var(--hover);
  border-radius: var(--radius);

  button {
    padding: 4px 10px;
    font-size: var(--text-small);
    line-height: 20px;
    font-weight: 500;
    color: var(--secondary);
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    cursor: pointer;

    &[aria-pressed="true"] {
      color: var(--ink);
      background: #FFFFFF;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
    }
  }
}

.note {
  max-width: 680px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

// Running text, at its measure, each turn beside who and when
.turns {
  display: grid;
  gap: 12px;
  max-width: 680px;
  padding: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 96px minmax(0, 1fr);
    column-gap: 16px;
  }
}

.who {
  display: flex;
  flex-direction: column;
  font-size: var(--text-caption);
  line-height: 22px;

  .name {
    font-weight: 600;
  }

  .second & .name {
    color: var(--secondary);
  }
}

.at {
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  line-height: 16px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

a.at {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}

@media (max-width: 640px) {
  .turns li {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 2px;
  }

  .who {
    flex-direction: row;
    align-items: baseline;
    gap: 8px;
  }
}
</style>
