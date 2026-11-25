<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { faArrowUpRightFromSquare } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { errorMessage } from '@/graphql'
import { formatWhen } from '@/transcriptions'
import { useLocale } from '@/useLocale'
import { isOn, uploadLine } from '@/youtube'
import type { ChannelUpload } from '@/youtubeChannel'
import { cancelYouTubeUpload } from '@/youtubeUploads'

// One upload of an episode's video file to YouTube: its episode and file, what it's sent as, how far it is, who asked
// and when; one that's on is cancelled here, and one that's there leads to its video in YouTube Studio, where it's
// published
const props = defineProps<{ upload: ChannelUpload }>()
const emit = defineEmits<{ changed: [] }>()
const { t, dateLocale, path } = useLocale()

const job = computed(() => props.upload)
const on = computed(() => isOn(job.value))
const episodeLink = computed(() => (job.value.episode ? path(`/episodes/${job.value.episode.code ?? job.value.episode.id}`) : null))
const busy = ref(false)
const error = ref<string>()

async function cancel() {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await cancelYouTubeUpload(job.value.id)
    emit('changed')
  } catch (e) {
    error.value = t('youtube.cancelFailed', { error: errorMessage(e, t) })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <li class="upload" :class="job.status.toLowerCase()">
    <span class="code" :aria-hidden="!job.episode?.code">{{ job.episode?.code ?? '—' }}</span>
    <div class="what">
      <RouterLink v-if="episodeLink" :to="episodeLink" class="title" lang="sl">{{ job.title }}</RouterLink>
      <span v-else class="title" lang="sl">{{ job.title }}</span>
      <span class="file">{{ job.mediaName }}</span>
    </div>
    <div class="how">
      <span class="status" :role="on ? 'status' : undefined">{{ uploadLine(job, t) }}</span>
    </div>
    <div class="when">{{ formatWhen(job.createdAt, dateLocale) }}<span v-if="job.requestedBy"> · {{ job.requestedBy }}</span></div>
    <div class="actions">
      <button v-if="on" type="button" class="button small" :aria-disabled="busy || undefined" @click="cancel">{{ t('youtube.cancel') }}</button>
      <a v-if="job.video" class="button small quiet" :href="job.video.studioUrl" target="_blank" rel="noopener" :aria-label="t('youtube.studio')">
        Studio<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </li>
</template>

<style scoped lang="scss">
// As a transcription's row: its episode's code in a column of its own, what, how far, when and by whom, and what may be done
.upload {
  display: grid;
  grid-template-columns: 72px minmax(0, 2fr) minmax(0, 2fr) minmax(0, 1.2fr) 176px;
  column-gap: 16px;
  row-gap: 4px;
  align-items: start;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
  font-size: var(--text-small);
  line-height: 20px;
}

.what {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title {
  font-size: var(--text-body);
  font-weight: 500;
  color: var(--ink);
  overflow-wrap: anywhere;
}

a.title:hover {
  color: var(--accent-text);
}

.code {
  font-family: var(--font-mono);
  color: var(--muted);
}

.file, .when {
  color: var(--muted);
  overflow-wrap: anywhere;
}

.status {
  font-weight: 500;
}

.failed .status {
  color: var(--accent-text);
}

.cancelled .status {
  color: var(--muted);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;

  a {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
}

.external {
  width: 10px;
  height: 10px;
}

.error {
  grid-column: 2 / -1;
  color: var(--accent-text);
}

@media (max-width: 640px) {
  .upload {
    grid-template-columns: 60px minmax(0, 1fr);
  }

  .what, .how, .when, .actions, .error {
    grid-column: 2;
  }

  .actions {
    justify-content: flex-start;
  }
}
</style>
