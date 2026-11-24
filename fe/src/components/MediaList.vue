<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { faFileAudio, faFileVideo } from '@fortawesome/free-regular-svg-icons'
import { faArrowDown, faArrowUpRightFromSquare } from '@fortawesome/free-solid-svg-icons'
import { faYoutube } from '@fortawesome/free-brands-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { formatDate } from '@/episodes'
import { errorMessage } from '@/graphql'
import { infoLine, KINDS, kindName, type MediaFile, type MediaKind } from '@/media'
import { downloadUrl, removeMedia } from '@/mediaUploads'
import { useLocale } from '@/useLocale'
import { fileOnYouTube, isOn, privacyName, uploadLine, type YouTubeUpload, type YouTubeVideo } from '@/youtube'
import { cancelYouTubeUpload, uploadToYouTube } from '@/youtubeUploads'

// An episode's video and audio, for the team: a row for its full video and full audio, which, missing, opens the
// upload for it, then its bonuses, each with what's in it, who uploaded it, and Download and Remove. Removing asks
// first, on its row, as it deletes the file. A video also says what YouTube has of it (the videos MetaOS sent there
// from it, and an upload that's on or failed), and has Send to YouTube, which asks for a title and a description on
// its row and sends it as a private video.
const props = withDefaults(defineProps<{
  media: MediaFile[]
  // The episode's ID, and its videos on YouTube and its files' uploads there
  episode?: string
  videos?: YouTubeVideo[]
  uploads?: YouTubeUpload[]
}>(), { videos: () => [], uploads: () => [] })

const emit = defineEmits<{
  // To upload one of them, e.g. the missing full audio
  upload: [kind: MediaKind]
  // One was removed
  changed: []
}>()

const { t, dateLocale } = useLocale()
const id = useId()

interface Group {
  kind: MediaKind
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

const youtube = (file: MediaFile) => fileOnYouTube(file.id, props.videos, props.uploads)

// The file being sent to YouTube, with what it's sent as
const sending = ref<string>()
const title = ref('')
const description = ref('')

function askToSend(file: MediaFile) {
  error.value = undefined
  title.value = ''
  description.value = ''
  sending.value = file.id
  void nextTick(() => document.getElementById(`${id}-title-${file.id}`)?.focus())
}

function dontSend(file: MediaFile) {
  sending.value = undefined
  void nextTick(() => document.getElementById(`${id}-send-${file.id}`)?.focus())
}

async function send(file: MediaFile) {
  if (busy.value || !props.episode) return
  error.value = undefined
  busy.value = file.id
  try {
    await uploadToYouTube(props.episode, file.id, { title: title.value.trim(), description: description.value.trim() || undefined })
    sending.value = undefined
    emit('changed')
  } catch (e) {
    error.value = { id: file.id, message: t('youtube.sendFailed', { error: errorMessage(e, t) }) }
  } finally {
    busy.value = undefined
  }
}

async function cancelUpload(file: MediaFile, upload: YouTubeUpload) {
  if (busy.value) return
  error.value = undefined
  busy.value = file.id
  try {
    await cancelYouTubeUpload(upload.id)
    emit('changed')
  } catch (e) {
    error.value = { id: file.id, message: t('youtube.cancelFailed', { error: errorMessage(e, t) }) }
  } finally {
    busy.value = undefined
  }
}

async function download(file: MediaFile) {
  error.value = undefined
  busy.value = file.id
  try {
    // Saved as a file, by its name, as Cloud Storage says (Content-Disposition), so the page stays
    window.location.assign(await downloadUrl(file.id))
  } catch (e) {
    error.value = { id: file.id, message: t('media.list.downloadFailed', { error: errorMessage(e, t) }) }
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
    error.value = { id: file.id, message: t('media.list.removeFailed', { error: errorMessage(e, t) }) }
  } finally {
    busy.value = undefined
  }
}
</script>

<template>
  <div class="media">
    <section v-for="group in groups" :key="group.kind" class="group" :aria-labelledby="`${id}-${group.kind}`">
      <h3 :id="`${id}-${group.kind}`">{{ group.full ? t(`media.kind.${group.kind}`) : t(`media.bonuses.${group.kind}`) }}<span v-if="!group.full" class="count">{{ group.files.length }}</span></h3>
      <button v-if="!group.files.length" type="button" class="missing" aria-haspopup="dialog" @click="emit('upload', group.kind)">
        <FontAwesomeIcon :icon="group.video ? faFileVideo : faFileAudio"/>{{ t('media.list.uploadKind', { name: kindName(group.kind, t) }) }}
      </button>
      <ul v-else class="files">
        <li v-for="file in group.files" :key="file.id" class="file">
          <span class="icon"><FontAwesomeIcon :icon="group.video ? faFileVideo : faFileAudio"/></span>
          <span class="text">
            <span v-if="file.title" class="title" lang="sl">{{ file.title }}</span>
            <span class="name">{{ file.name }}</span>
            <span class="info">{{ infoLine(file.info, file.size, dateLocale) }}</span>
            <span class="by">{{ file.uploadedBy }}, <time :datetime="file.uploadedAt">{{ formatDate(file.uploadedAt, dateLocale) }}</time></span>
            <template v-if="group.video">
              <span v-for="video in youtube(file).videos" :key="video.id" class="youtube">
                <FontAwesomeIcon :icon="faYoutube"/>{{ t('youtube.on') }} · {{ privacyName(video.privacy, t) }} ·
                <a :href="video.studioUrl" target="_blank" rel="noopener">{{ t('youtube.studio') }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/></a>
              </span>
              <span v-if="youtube(file).upload" class="youtube" :class="{ failed: youtube(file).upload!.status === 'FAILED' }" role="status">
                <FontAwesomeIcon :icon="faYoutube"/>{{ uploadLine(youtube(file).upload!, t) }}
                <button v-if="isOn(youtube(file).upload!)" type="button" class="text-button" :disabled="busy === file.id"
                        @click="cancelUpload(file, youtube(file).upload!)">{{ t('youtube.cancel') }}</button>
              </span>
            </template>
            <span v-if="error?.id === file.id" class="error" role="alert">{{ error.message }}</span>
          </span>
          <span v-if="confirming === file.id" class="confirm" role="group" :aria-labelledby="`${id}-ask-${file.id}`" @keydown.esc="keep(file)">
            <span :id="`${id}-ask-${file.id}`" class="question">{{ t('media.list.ask') }}</span>
            <button type="button" class="button delete" :aria-disabled="busy === file.id || undefined" @click="remove(file)">
              {{ busy === file.id ? t('media.list.removing') : t('common.remove') }}
            </button>
            <button :id="`${id}-keep-${file.id}`" type="button" class="button" :disabled="busy === file.id" @click="keep(file)">{{ t('common.cancel') }}</button>
          </span>
          <span v-else class="actions">
            <button type="button" class="button small" :aria-disabled="busy === file.id || undefined" @click="download(file)">
              <FontAwesomeIcon :icon="faArrowDown"/>{{ t('media.list.download') }}
            </button>
            <button v-if="group.video && episode && !(youtube(file).upload && isOn(youtube(file).upload!))" :id="`${id}-send-${file.id}`"
                    type="button" class="button small" :aria-expanded="sending === file.id" @click="sending === file.id ? dontSend(file) : askToSend(file)">
              <FontAwesomeIcon :icon="faYoutube"/>{{ youtube(file).videos.length ? t('youtube.sendAgain') : t('youtube.send') }}
            </button>
            <button :id="`${id}-remove-${file.id}`" type="button" class="button small quiet" @click="askToRemove(file)">{{ t('common.remove') }}</button>
          </span>
          <form v-if="sending === file.id" class="send" :aria-label="t('youtube.form', { name: file.name })" @submit.prevent="send(file)" @keydown.esc="dontSend(file)">
            <label :for="`${id}-title-${file.id}`">{{ t('youtube.title') }}</label>
            <input :id="`${id}-title-${file.id}`" v-model="title" class="input" type="text" maxlength="100" lang="sl" :aria-describedby="`${id}-title-hint-${file.id}`">
            <p :id="`${id}-title-hint-${file.id}`" class="hint">{{ t('youtube.titleHint') }}</p>
            <label :for="`${id}-description-${file.id}`">{{ t('youtube.description') }}</label>
            <textarea :id="`${id}-description-${file.id}`" v-model="description" class="input" rows="5" lang="sl" :aria-describedby="`${id}-description-hint-${file.id}`"/>
            <p :id="`${id}-description-hint-${file.id}`" class="hint">{{ t('youtube.descriptionHint') }}</p>
            <p class="hint">{{ t('youtube.privateNote') }}</p>
            <span class="buttons">
              <button type="submit" class="button primary small" :aria-disabled="busy === file.id || undefined">{{ busy === file.id ? t('youtube.sending') : t('youtube.start') }}</button>
              <button type="button" class="button small" :disabled="busy === file.id" @click="dontSend(file)">{{ t('common.cancel') }}</button>
            </span>
          </form>
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

// What YouTube has of the file: a line each, under who uploaded it
.youtube {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
  color: var(--secondary);

  &.failed {
    color: var(--accent-text);
  }

  a {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .external {
    width: 10px;
    height: 10px;
  }
}

// What it's sent to YouTube as, asked on its row, under it
.send {
  grid-column: 2 / -1;
  display: grid;
  gap: 4px;
  max-width: 560px;
  padding-top: 4px;
  font-size: var(--text-caption);
  line-height: 18px;

  label {
    margin-top: 6px;
    font-weight: 600;
  }

  textarea {
    height: auto;
    padding-block: 6px;
    line-height: 20px;
    resize: vertical;
  }

  .hint {
    color: var(--muted);
  }

  .buttons {
    display: flex;
    gap: 6px;
    margin-top: 8px;
  }
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
