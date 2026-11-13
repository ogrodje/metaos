<script setup lang="ts">
import { onMounted, ref } from 'vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import { graphql, UserError } from '@/graphql'
import { useLocale } from '@/useLocale'
import { speakerName, timestamp, TRANSCRIPT_LINES, type TranscriptLine, type TranscriptSpeaker } from '@/transcriptions'

// What a transcript says, a line a stretch, each from where it starts and, where the provider tells them apart, by
// whom; loaded once it's opened, as it's the whole episode
const props = defineProps<{ id: string, language: string }>()
const { t } = useLocale()

const lines = ref<TranscriptLine[]>()
const speakers = ref<TranscriptSpeaker[]>([])
const error = ref<unknown>()

async function load() {
  error.value = undefined
  try {
    const { transcript } = await graphql<{ transcript: { lines: TranscriptLine[], speakers: TranscriptSpeaker[] } | null }>(
      TRANSCRIPT_LINES, { id: props.id })
    if (!transcript) throw new UserError('transcriptions.lines.removed')
    speakers.value = transcript.speakers
    lines.value = transcript.lines
  } catch (e) {
    error.value = e
  }
}

onMounted(load)
</script>

<template>
  <LoadError v-if="error" :what="t('transcriptions.lines.what')" :error="error" @retry="load()"/>
  <LoadingRows v-else-if="!lines" :rows="4" :label="t('transcriptions.lines.loading')"/>
  <p v-else-if="!lines.length" class="none">{{ t('transcriptions.lines.none') }}</p>
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

<style scoped lang="scss">
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
