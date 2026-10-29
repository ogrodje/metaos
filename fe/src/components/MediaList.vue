<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { faFileAudio, faFileVideo } from '@fortawesome/free-regular-svg-icons'
import { faArrowDown } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { formatDate } from '@/episodes'
import { errorMessage } from '@/graphql'
import { infoLine, KINDS, type MediaFile, type MediaKind } from '@/media'
import { downloadUrl, removeMedia } from '@/mediaUploads'

// An episode's video and audio, for the team: a row for its full video and full audio, which, missing, opens the
// upload for it, then its bonuses, each with what's in it, who uploaded it, and Download and Remove. Removing asks
// first, on its row, as it deletes the file.
const props = defineProps<{
  media: MediaFile[]
}>()

const emit = defineEmits<{
  // To upload one of them, e.g. the missing full audio
  upload: [kind: MediaKind]
  // One was removed
  changed: []
}>()

const id = useId()

interface Group {
  kind: MediaKind
  label: string
  video: boolean
  full: boolean
  files: MediaFile[]
}

// Those removed here, gone at once rather than once the page has loaded again
const removed = ref<string[]>([])

// The full ones always, as what's missing shows; the bonuses when there are some
const groups = computed<Group[]>(() => KINDS
  .map((kind) => ({ ...kind, files: props.media.filter((file) => file.kind === kind.kind && !removed.value.includes(file.id)) }))
  .filter((group) => group.full || group.files.length))

const confirming = ref<string>()
const busy = ref<string>()
const error = ref<{ id: string, message: string }>()

async function download(file: MediaFile) {
  error.value = undefined
  busy.value = file.id
  try {
    // Saved as a file, by its name, as Cloud Storage says (Content-Disposition), so the page stays
    window.location.assign(await downloadUrl(file.id))
  } catch (e) {
    error.value = { id: file.id, message: `It didn't download: ${errorMessage(e)}` }
  } finally {
    busy.value = undefined
  }
}

function askToRemove(file: MediaFile) {
  error.value = undefined
  confirming.value = file.id
  void nextTick(() => document.getElementById(`${id}-keep-${file.id}`)?.focus())
}

function keep(file: MediaFile) {
  confirming.value = undefined
  void nextTick(() => document.getElementById(`${id}-remove-${file.id}`)?.focus())
}

async function remove(file: MediaFile) {
  if (busy.value) return
  busy.value = file.id
  try {
    await removeMedia(file.id)
    removed.value.push(file.id)
    confirming.value = undefined
    emit('changed')
  } catch (e) {
    error.value = { id: file.id, message: `It wasn't removed: ${errorMessage(e)}` }
  } finally {
    busy.value = undefined
  }
}
</script>

<template>
  <div class="media">
    <section v-for="group in groups" :key="group.kind" class="group" :aria-labelledby="`${id}-${group.kind}`">
      <h3 :id="`${id}-${group.kind}`">{{ group.full ? group.label : `${group.label}s` }}<span v-if="!group.full" class="count">{{ group.files.length }}</span></h3>
      <button v-if="!group.files.length" type="button" class="missing" aria-haspopup="dialog" @click="emit('upload', group.kind)">
        <FontAwesomeIcon :icon="group.video ? faFileVideo : faFileAudio"/>Upload the {{ group.label.toLowerCase() }}
      </button>
      <ul v-else class="files">
        <li v-for="file in group.files" :key="file.id" class="file">
          <span class="icon"><FontAwesomeIcon :icon="group.video ? faFileVideo : faFileAudio"/></span>
          <span class="text">
            <span v-if="file.title" class="title" lang="sl">{{ file.title }}</span>
            <span class="name">{{ file.name }}</span>
            <span class="info">{{ infoLine(file.info, file.size) }}</span>
            <span class="by">{{ file.uploadedBy }}, <time :datetime="file.uploadedAt">{{ formatDate(file.uploadedAt) }}</time></span>
            <span v-if="error?.id === file.id" class="error" role="alert">{{ error.message }}</span>
          </span>
          <span v-if="confirming === file.id" class="confirm" role="group" :aria-labelledby="`${id}-ask-${file.id}`" @keydown.esc="keep(file)">
            <span :id="`${id}-ask-${file.id}`" class="question">Remove it, and delete the file?</span>
            <button type="button" class="button delete" :aria-disabled="busy === file.id || undefined" @click="remove(file)">
              {{ busy === file.id ? 'Removing…' : 'Remove' }}
            </button>
            <button :id="`${id}-keep-${file.id}`" type="button" class="button" :disabled="busy === file.id" @click="keep(file)">Cancel</button>
          </span>
          <span v-else class="actions">
            <button type="button" class="button small" :aria-disabled="busy === file.id || undefined" @click="download(file)">
              <FontAwesomeIcon :icon="faArrowDown"/>Download
            </button>
            <button :id="`${id}-remove-${file.id}`" type="button" class="button small quiet" @click="askToRemove(file)">Remove</button>
          </span>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped lang="scss">
.media {
  display: grid;
  gap: 18px;
}

h3 {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);

  .count {
    font-weight: 500;
  }
}

.files {
  padding: 0;
  list-style: none;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

.file {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 12px;

  & + & {
    border-top: 1px solid var(--rule);
  }
}

.icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  color: var(--secondary);
  background: var(--hover);
  border-radius: var(--radius);

  svg {
    width: 16px;
    height: 16px;
  }
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
  font-size: var(--text-caption);
  line-height: 18px;
}

.title {
  font-size: var(--text-body);
  line-height: 20px;
  font-weight: 600;
}

.name {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  font-weight: 500;
  overflow-wrap: anywhere;
}

.info {
  color: var(--secondary);
  font-variant-numeric: tabular-nums;
}

.by {
  color: var(--muted);
}

.error {
  color: var(--accent-text);
}

.actions,
.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.question {
  font-size: var(--text-caption);
  font-weight: 500;
}

.button.delete {
  color: var(--accent-text);
}

.button[aria-disabled='true'] {
  color: var(--muted);
  cursor: default;
}

// A place to upload one: dashed, as a missing picture's is
.missing {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 60px;
  padding: 12px;
  font: inherit;
  font-size: var(--text-caption);
  color: var(--muted);
  background: var(--surface);
  border: 1px dashed var(--faint);
  border-radius: var(--radius);
  cursor: pointer;

  &:hover {
    color: var(--ink);
    border-color: var(--hairline-strong);
  }
}

@media (max-width: 640px) {
  .file {
    grid-template-columns: 36px minmax(0, 1fr);
  }

  .actions,
  .confirm {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
