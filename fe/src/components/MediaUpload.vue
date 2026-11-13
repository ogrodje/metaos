<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { faFileAudio, faFileVideo } from '@fortawesome/free-regular-svg-icons'
import {
  faArrowRight, faCheck, faCircleExclamation, faFolderOpen, faRightLeft, faRotateRight, faTriangleExclamation, faXmark,
} from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { droppedFiles } from '@/artwork'
import type { EpisodeDetails } from '@/episodes'
import {
  ACCEPT, formatDuration, formatFrameRate, formatSampleRate, formatSize, isMedia, isVideo, KINDS, kindLabel, kindName, kindOf,
  MAX_MEDIA_BYTES, type MediaFile, type MediaKind, type Picked, placeAll, type Probe, warnings,
} from '@/media'
import { readMedia, uploadMedia } from '@/mediaUploads'
import { stopped, uploadError } from '@/uploads'
import { useLocale } from '@/useLocale'

// Upload video and audio: an episode's full video and full audio, and its bonuses. Drop its files anywhere on it, or
// choose them, and each goes where what's in it and its name say (media.ts), which can be changed on its row; or open
// it for one of them. What MetaOS doesn't take is refused there and then, and what looks wrong is said; a full one that
// would replace the episode's waits for a yes, asked once before anything is sent. Each file is its own upload, in
// chunks, one at a time, so a refused or failed one holds nothing back. Closing it doesn't stop what's being sent;
// Stop does, as does leaving the page, which asks first.

const props = defineProps<{
  episode: EpisodeDetails
}>()

const emit = defineEmits<{
  // A file is the episode's now
  changed: []
}>()

type Upload =
  | { state: 'waiting' }
  | { state: 'uploading', percent: number }
  | { state: 'checking' }
  | { state: 'done', file: MediaFile }
  | { state: 'failed', message: string }

interface Row {
  picked: Picked
  kind: MediaKind
  title: string
  upload: Upload | null
  // Whether it may replace the episode's full one, once that was asked
  replace: boolean
}

const { t, dateLocale } = useLocale()

const id = useId()
const dialog = ref<HTMLDialogElement>()
const chooseFiles = ref<HTMLInputElement>()
const chooseFolder = ref<HTMLInputElement>()
// What the files chosen next are, when it was opened for one of them
const choosingFor = ref<MediaKind>()

const rows = ref<Row[]>([])
const refused = ref<{ picked: Picked, reason: string }[]>([])
const reading = ref(0)
// While it asks whether to replace, the rows that would, ticked to be replaced
const asking = ref<Row[]>()
const ticked = ref<number[]>([])
const busy = ref(false)
const announcement = ref('')
// Files are being dragged over it; counted, as each element they pass over says when they leave it
const dragging = ref(0)
let controller: AbortController | undefined

// The episode's full video or audio, which a new one replaces
const current = (kind: MediaKind) => (kindOf(kind).full ? props.episode.media.find((file) => file.kind === kind) ?? null : null)

type Status = 'new' | 'replace' | Upload['state']

function status(row: Row): Status {
  if (row.upload) return row.upload.state
  return current(row.kind) ? 'replace' : 'new'
}

const toSend = computed(() => rows.value.filter((row) => ['new', 'replace'].includes(status(row))))
const replacing = computed(() => toSend.value.filter((row) => status(row) === 'replace'))
const count = (state: Status) => rows.value.filter((row) => status(row) === state).length
const sending = computed(() => rows.value.filter((row) => row.upload && row.upload.state !== 'done' && row.upload.state !== 'failed').length)
const finished = computed(() => !busy.value && rows.value.some((row) => ['done', 'failed'].includes(status(row))))

const summary = computed(() => {
  const refusedNote = refused.value.length ? t('media.upload.refusedCount', { n: refused.value.length }) : null
  const line = (...parts: (string | null | false)[]) => parts.filter(Boolean).join(' · ')
  const done = count('done')
  const failed = count('failed')
  if (busy.value) return t('media.upload.summary.busy', { files: t('media.upload.sending', { n: sending.value }, sending.value), done })
  if (finished.value && !toSend.value.length) return line(t('media.upload.uploadedCount', { n: done }), !!failed && t('media.upload.failedCount', { n: failed }))
  if (!toSend.value.length) return refusedNote ? line(t('media.upload.nothingToUpload'), refusedNote) : t('media.upload.nothingChosen')
  const n = toSend.value.length
  const size = formatSize(toSend.value.reduce((sum, row) => sum + row.picked.file.size, 0), dateLocale.value)
  const r = replacing.value.length
  const toUpload = r
    ? t('media.upload.summary.replacing', { n, size, r }, r)
    : t('media.upload.summary.toUpload', { n, size })
  return line(toUpload, refusedNote)
})

// The kinds a row may be: of its medium, video or audio
const kindsFor = (row: Row) => KINDS.filter((kind) => kind.video === isVideo(row.picked))

// What's in it, as the browser read it, on one line
function probeLine(picked: Picked) {
  const probe: Probe | null = picked.probe
  const parts: string[] = []
  if (probe?.duration) parts.push(formatDuration(probe.duration))
  if (probe?.width && probe.height) parts.push(`${probe.width} × ${probe.height}`)
  if (probe?.frameRate) parts.push(formatFrameRate(probe.frameRate, dateLocale.value))
  if (probe?.videoFormat) parts.push(probe.videoFormat)
  if (probe?.audioFormat) {
    parts.push([probe.audioFormat, probe.sampleRate && formatSampleRate(probe.sampleRate, dateLocale.value)].filter(Boolean).join(' '))
  }
  parts.push(formatSize(picked.file.size, dateLocale.value))
  return parts.join(' · ')
}

// What looks wrong with it as what it's to be, and a full audio that isn't as long as the full video
function concerns(row: Row): string[] {
  const found = warnings(row.picked.probe, row.kind, t, dateLocale.value)
  const other: MediaKind | null = row.kind === 'FULL_AUDIO' ? 'FULL_VIDEO' : row.kind === 'FULL_VIDEO' ? 'FULL_AUDIO' : null
  const length = row.picked.probe?.duration
  if (other && length) {
    const otherRow = rows.value.find((it) => it.kind === other)
    const otherLength = otherRow ? otherRow.picked.probe?.duration : current(other)?.info.duration
    if (otherLength && Math.abs(otherLength - length) > 5) {
      const apart = formatDuration(Math.abs(otherLength - length))
      found.push(t(`media.warning.${length < otherLength ? 'shorterThan' : 'longerThan'}.${other}`, { length: apart }))
    }
  }
  return found
}

function percent(row: Row) {
  return row.upload?.state === 'uploading' ? row.upload.percent : 0
}

function failure(row: Row) {
  return row.upload?.state === 'failed' ? row.upload.message : ''
}

function reset() {
  rows.value = []
  refused.value = []
  asking.value = undefined
}

/** Opens it; for one of the episode's files, e.g. its missing full audio, when it's for one */
function open(kind?: MediaKind) {
  if (!dialog.value) return
  // Started afresh once what was sent last time is done
  if (!busy.value && !toSend.value.length) reset()
  choosingFor.value = kind
  if (!dialog.value.open) dialog.value.showModal()
  void nextTick(() => document.getElementById(`${id}-choose`)?.focus())
}

function close() {
  dialog.value?.close()
}

// Leaves what was chosen, unlike closing it
function cancel() {
  reset()
  close()
}

// Clicks on the backdrop are on the dialog itself; those in it are on its panel
function onDialogClick(event: MouseEvent) {
  if (event.target === dialog.value) close()
}

// Files dropped or chosen together: each where what's in it and its name say, keeping the full ones picked already; or
// all as the one it was opened for, when that's what they are
async function add(files: File[]) {
  if (busy.value || !files.length) return
  const media = files.filter(isMedia)
  reading.value = media.length
  let picked: Picked[]
  try {
    picked = []
    for (const file of media) {
      picked.push(await readMedia(file))
      reading.value--
    }
  } finally {
    reading.value = 0
  }
  const taken = rows.value.map((row) => row.kind)
  const placement = placeAll(picked, t, dateLocale.value, taken)
  const wanted = choosingFor.value
  for (const { picked: it, kind } of placement.placed) {
    const chosen = wanted && kindOf(wanted).video === isVideo(it) ? wanted : kind
    // A full one chosen takes the place of one picked before, which becomes a bonus
    if (kindOf(chosen).full) for (const row of rows.value) if (row.kind === chosen) row.kind = KINDS.find((k) => k.video === kindOf(chosen).video && !k.full)!.kind
    rows.value.push({ picked: it, kind: chosen, title: '', upload: null, replace: false })
  }
  // The full ones first, as they're what the episode needs, and sent in this order
  rows.value.sort((a, b) => KINDS.indexOf(kindOf(a.kind)) - KINDS.indexOf(kindOf(b.kind)))
  refused.value.push(...placement.refused)
  const leftOut = files.length - media.length
  choosingFor.value = undefined
  announcement.value = [
    t('media.upload.announce.added', { n: placement.placed.length }),
    placement.refused.length && t('media.upload.announce.refused', { n: placement.refused.length }),
    leftOut && t('media.upload.leftOut', { n: leftOut }, leftOut),
  ].filter(Boolean).join(', ')
}

async function onDrop(event: DragEvent) {
  dragging.value = 0
  if (!event.dataTransfer) return
  await add((await droppedFiles(event.dataTransfer.items)).files)
}

async function onChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  await add(files)
}

// What a row is to be: a full one takes the place of another row's, which becomes a bonus
function setKind(row: Row, kind: string) {
  const next = kind as MediaKind
  if (kindOf(next).full) for (const other of rows.value) if (other !== row && other.kind === next) other.kind = KINDS.find((k) => k.video === kindOf(next).video && !k.full)!.kind
  row.kind = next
}

function leaveOut(row: Row) {
  rows.value = rows.value.filter((it) => it !== row)
}

function upload() {
  if (busy.value || !toSend.value.length) return
  if (replacing.value.length) {
    asking.value = [...replacing.value]
    ticked.value = asking.value.map((row) => row.picked.id)
    void nextTick(() => document.getElementById(`${id}-ask`)?.focus())
  } else {
    void send(toSend.value)
  }
}

// After the question: those not ticked are left out, and the rest goes
const keeping = computed(() => (asking.value?.length ?? 0) - ticked.value.length)
const answerLabel = computed(() => {
  const left = toSend.value.length - keeping.value
  const kept = keeping.value
  if (!kept) return t('media.upload.answer.replace', { n: left })
  return left ? t('media.upload.answer.uploadAndKeep', { n: left, kept }, kept) : t('media.upload.answer.keep', { kept }, kept)
})

function answer() {
  for (const row of asking.value ?? []) {
    if (ticked.value.includes(row.picked.id)) row.replace = true
    else leaveOut(row)
  }
  asking.value = undefined
  void send(toSend.value)
}

// One at a time, as they're listed
async function send(toGo: Row[]) {
  if (!toGo.length) return
  busy.value = true
  controller = new AbortController()
  const { signal } = controller
  for (const row of toGo) row.upload = { state: 'waiting' }
  try {
    for (const row of toGo) {
      if (signal.aborted) {
        row.upload = null
        continue
      }
      try {
        const title = kindOf(row.kind).full ? null : row.title.trim() || null
        const file = await uploadMedia(props.episode.id, row.kind, row.picked, title, row.replace, (progress) => {
          row.upload = progress.state === 'uploading' ? { state: 'uploading', percent: progress.percent } : { state: 'checking' }
        }, signal)
        row.upload = { state: 'done', file }
        announcement.value = t('media.upload.announce.uploaded', { name: row.picked.name })
        emit('changed')
      } catch (error) {
        row.upload = stopped(error) ? null : { state: 'failed', message: uploadError(error, t) }
        if (row.upload) announcement.value = t('media.upload.announce.failed', { name: row.picked.name, message: row.upload.message })
      }
    }
  } finally {
    busy.value = false
    controller = undefined
  }
}

function stop() {
  controller?.abort()
}

function retry(row?: Row) {
  const failed = rows.value.filter((it) => status(it) === 'failed' && (!row || it === row))
  for (const it of failed) it.upload = null
  void send(failed)
}

// Leaving while something's being sent stops it, so it's asked first: the page, and MetaOS's other pages
function onBeforeUnload(event: BeforeUnloadEvent) {
  if (busy.value) event.preventDefault()
}

onBeforeRouteLeave(() => !busy.value || window.confirm(t('media.upload.leave')))

onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  controller?.abort()
})

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="upload-dialog" :aria-labelledby="`${id}-title`" @click="onDialogClick"
          @dragenter.prevent="dragging++" @dragleave="dragging = Math.max(0, dragging - 1)" @dragover.prevent @drop.prevent="onDrop">
    <div class="panel" :class="{ dragging: dragging > 0 && !busy }">
      <header class="head">
        <div class="names">
          <h2 :id="`${id}-title`">{{ t('media.upload.title') }}</h2>
          <p class="sub"><span v-if="episode.code" class="code">{{ episode.code }}</span><span lang="sl">{{ episode.title }}</span></p>
        </div>
        <!-- Esc on a keyboard, Cancel on a phone -->
        <button type="button" class="close" :aria-label="t('common.close')" @click="close()">
          <kbd aria-hidden="true">esc</kbd><span class="cancel" aria-hidden="true">{{ t('common.close') }}</span>
        </button>
      </header>

      <div class="body">
        <div v-if="!rows.length" class="drop">
          <FontAwesomeIcon :icon="faFileVideo" class="big-icon"/>
          <p class="big">
            <span class="desk">{{ choosingFor ? t('media.upload.dropKind', { name: kindName(choosingFor, t) }) : t('media.upload.drop') }}</span>
            <span class="touch">{{ choosingFor ? t('media.upload.chooseKind', { name: kindName(choosingFor, t) }) : t('media.upload.dropTouch') }}</span>
          </p>
          <p class="small">{{ t('media.upload.formats', { max: formatSize(MAX_MEDIA_BYTES, dateLocale) }) }}</p>
          <div class="buttons">
            <button :id="`${id}-choose`" type="button" class="button" @click="chooseFiles?.click()">
              <FontAwesomeIcon :icon="faFileVideo"/>{{ choosingFor ? t('media.upload.chooseKind', { name: kindName(choosingFor, t) }) : t('media.upload.choose') }}
            </button>
            <button v-if="!choosingFor" type="button" class="button" @click="chooseFolder?.click()"><FontAwesomeIcon :icon="faFolderOpen"/>{{ t('media.upload.chooseFolder') }}</button>
          </div>
        </div>
        <div v-else class="dropbar">
          <FontAwesomeIcon :icon="faFolderOpen"/>
          <span class="grow">{{ t('media.upload.files', { n: rows.length }, rows.length) }}</span>
          <button :id="`${id}-choose`" type="button" class="button small quiet" :disabled="busy" @click="chooseFiles?.click()">{{ t('media.upload.addMore') }}</button>
        </div>
        <p v-if="reading" class="note" role="status">{{ t('media.upload.reading', { n: reading }, reading) }}</p>

        <ul v-if="rows.length" class="slots" :aria-label="t('media.upload.list')">
          <li v-for="row in rows" :key="row.picked.id" class="slot">
            <span class="kind-icon" :class="{ new: ['new', 'replace'].includes(status(row)) }">
              <FontAwesomeIcon :icon="isVideo(row.picked) ? faFileVideo : faFileAudio"/>
            </span>
            <span class="text">
              <span class="name"><b class="file-name">{{ row.picked.name }}</b></span>
              <span class="line">{{ probeLine(row.picked) }}</span>
              <span class="line choose">
                <label :for="`${id}-kind-${row.picked.id}`">{{ t('media.upload.as') }}</label>
                <select :id="`${id}-kind-${row.picked.id}`" :value="row.kind" :disabled="busy || !!row.upload"
                        @change="setKind(row, ($event.target as HTMLSelectElement).value)">
                  <option v-for="kind in kindsFor(row)" :key="kind.kind" :value="kind.kind">{{ kindLabel(kind.kind, t) }}</option>
                </select>
                <template v-if="!kindOf(row.kind).full">
                  <label class="visually-hidden" :for="`${id}-title-${row.picked.id}`">{{ t('media.upload.titleOf', { name: row.picked.name }) }}</label>
                  <input :id="`${id}-title-${row.picked.id}`" v-model="row.title" class="title-input" type="text" maxlength="200"
                         :placeholder="t('media.upload.titlePlaceholder')" :disabled="busy || !!row.upload" lang="sl">
                </template>
              </span>
              <span v-for="concern in concerns(row)" :key="concern" class="line warn"><FontAwesomeIcon :icon="faTriangleExclamation"/>{{ concern }}</span>
              <span class="line state">
                <template v-if="status(row) === 'new'"><span class="st new"><i/>{{ t('media.upload.new') }}</span></template>
                <template v-else-if="status(row) === 'replace'"><span class="st"><FontAwesomeIcon :icon="faRightLeft"/>{{ t('media.upload.replaces') }}</span><span class="file">{{ current(row.kind)!.name }}</span></template>
                <template v-else-if="status(row) === 'waiting'"><span class="st hollow"><i/>{{ t('media.upload.waiting') }}</span><span>{{ t('media.upload.afterPrevious') }}</span></template>
                <template v-else-if="status(row) === 'uploading'">
                  <span class="st">{{ t('media.upload.uploading', { percent: percent(row) }) }}</span>
                  <span class="progress" role="progressbar" :aria-valuenow="percent(row)" aria-valuemin="0" aria-valuemax="100" :aria-label="row.picked.name"><b :style="{ width: `${percent(row)}%` }"/></span>
                </template>
                <template v-else-if="status(row) === 'checking'"><span class="st"><span class="spin" aria-hidden="true"/>{{ t('media.upload.checking') }}</span><span>{{ t('media.upload.checkingWhat') }}</span></template>
                <template v-else-if="status(row) === 'done'"><span class="st ok"><FontAwesomeIcon :icon="faCheck"/>{{ t('media.upload.uploaded') }}</span></template>
                <template v-else-if="status(row) === 'failed'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>{{ t('media.upload.failed') }}</span><span>{{ failure(row) }}</span></template>
              </span>
            </span>
            <span class="actions">
              <button v-if="status(row) === 'failed'" type="button" class="button small" :disabled="busy" @click="retry(row)"><FontAwesomeIcon :icon="faRotateRight"/>{{ t('common.tryAgain') }}</button>
              <button v-if="['new', 'replace', 'failed'].includes(status(row))" type="button" class="iconbutton" :disabled="busy"
                      :aria-label="t('media.upload.leaveOut', { name: row.picked.name })" @click="leaveOut(row)"><FontAwesomeIcon :icon="faXmark"/></button>
            </span>
          </li>
        </ul>

        <template v-if="refused.length">
          <p class="group">{{ t('media.upload.fitsNone') }}</p>
          <ul class="slots">
            <li v-for="{ picked, reason } in refused" :key="picked.id" class="slot">
              <span class="kind-icon refused"><FontAwesomeIcon :icon="faCircleExclamation"/></span>
              <span class="text">
                <span class="name"><b class="file-name">{{ picked.name }}</b></span>
                <span class="line state"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>{{ t('media.upload.refused') }}</span><span>{{ reason }}</span></span>
              </span>
              <span class="actions">
                <button type="button" class="iconbutton" :aria-label="t('media.upload.leaveOut', { name: picked.name })" @click="refused = refused.filter((it) => it.picked.id !== picked.id)"><FontAwesomeIcon :icon="faXmark"/></button>
              </span>
            </li>
          </ul>
        </template>
      </div>

      <div v-if="asking" class="ask" role="group" :aria-labelledby="`${id}-ask`">
        <p :id="`${id}-ask`" class="question" tabindex="-1">
          {{ asking.length === 1
            ? t('media.upload.askOne', { name: kindName(asking[0]!.kind, t), episode: episode.code ?? t('media.upload.theEpisode') })
            : t('media.upload.ask', { n: asking.length, episode: episode.code ?? t('media.upload.theEpisode') }, asking.length) }}
        </p>
        <label v-for="row in asking" :key="row.picked.id" class="replacement" :class="{ on: ticked.includes(row.picked.id) }">
          <input v-model="ticked" type="checkbox" :value="row.picked.id">
          <span>
            <b>{{ kindLabel(row.kind, t) }}</b>
            <span class="swap">
              <span class="was file">{{ current(row.kind)!.name }}</span>
              <FontAwesomeIcon :icon="faArrowRight" class="arrow"/>
              <span class="becomes file">{{ row.picked.name }}</span>
            </span>
          </span>
        </label>
        <p class="note">{{ t('media.upload.history') }}</p>
        <div class="buttons end">
          <button type="button" class="button quiet" @click="asking = undefined">{{ t('media.upload.back') }}</button>
          <button type="button" class="button primary" @click="answer()">{{ answerLabel }}</button>
        </div>
      </div>
      <div v-else class="foot">
        <span class="summary" aria-live="polite">{{ summary }}</span>
        <span class="buttons">
          <template v-if="busy"><button type="button" class="button quiet" @click="stop()">{{ t('media.upload.stop') }}</button></template>
          <template v-else-if="finished && !toSend.length">
            <button v-if="count('failed')" type="button" class="button" @click="retry()"><FontAwesomeIcon :icon="faRotateRight"/>{{ t('common.tryAgain') }}</button>
            <button type="button" class="button primary" @click="close()">{{ t('media.upload.done') }}</button>
          </template>
          <template v-else>
            <button type="button" class="button quiet" @click="cancel()">{{ t('common.cancel') }}</button>
            <button type="button" class="button primary" :aria-disabled="!toSend.length || reading > 0" @click="reading || upload()">{{ toSend.length ? t('media.upload.uploadN', { n: toSend.length }) : t('media.upload.upload') }}</button>
          </template>
        </span>
      </div>
      <p class="visually-hidden" aria-live="polite">{{ announcement }}</p>
    </div>

    <input ref="chooseFiles" type="file" :accept="ACCEPT" :multiple="!choosingFor || !kindOf(choosingFor).full" hidden @change="onChosen">
    <input ref="chooseFolder" type="file" webkitdirectory hidden @change="onChosen">
  </dialog>
</template>

<style scoped lang="scss">
@use '../assets/upload-dialog';

// A row: what kind of file it is, rather than a picture of it
.slot {
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: start;
}

.kind-icon {
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

  &.new {
    color: var(--ink);
    box-shadow: inset 0 0 0 1.5px var(--accent);
  }

  &.refused {
    color: var(--accent-text);
    background: var(--accent-wash);
  }
}

.text {
  gap: 3px;
}

.line {
  font-variant-numeric: tabular-nums;
}

.choose {
  margin-top: 2px;

  label {
    color: var(--secondary);
  }
}

.title-input {
  flex: 1;
  min-width: 160px;
  min-height: 32px;
  padding: 0 8px;
  font: inherit;
  font-size: var(--text-caption);
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
}

.warn {
  gap: 6px;
  color: var(--warning);

  svg {
    width: 12px;
    height: 12px;
  }
}

// The file replaced, then the new one
.swap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  margin-top: 6px;

  .arrow {
    color: var(--muted);
  }

  .was,
  .becomes {
    transition: opacity .15s;
  }
}

@media (max-width: 640px) {
  .slot {
    grid-template-columns: 36px minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .swap .was,
  .swap .becomes {
    transition: none;
  }
}
</style>
