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
  ACCEPT, AUDIO_FORMATS, formatDuration, formatSize, isMedia, isVideo, KINDS, kindOf, MAX_MEDIA_BYTES, type MediaFile,
  type MediaKind, type Picked, placeAll, type Probe, VIDEO_FORMATS, warnings,
} from '@/media'
import { readMedia, uploadMedia } from '@/mediaUploads'
import { stopped, uploadError } from '@/uploads'

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
  const refusedNote = refused.value.length ? ` · ${refused.value.length} refused` : ''
  if (busy.value) return `Uploading ${sending.value} ${sending.value === 1 ? 'file' : 'files'} · ${count('done')} uploaded`
  if (finished.value && !toSend.value.length) return [`${count('done')} uploaded`, count('failed') && `${count('failed')} didn't`].filter(Boolean).join(' · ')
  if (!toSend.value.length) return refused.value.length ? `Nothing to upload${refusedNote}` : 'Nothing chosen yet'
  const n = toSend.value.length
  const bytes = formatSize(toSend.value.reduce((sum, row) => sum + row.picked.file.size, 0))
  const replaces = replacing.value.length ? `, replacing ${replacing.value.length === 1 ? 'one' : replacing.value.length}` : ''
  return `${n} to upload, ${bytes}${replaces}${refusedNote}`
})

// The kinds a row may be: of its medium, video or audio
const kindsFor = (row: Row) => KINDS.filter((kind) => kind.video === isVideo(row.picked))

// What's in it, as the browser read it, on one line
function probeLine(picked: Picked) {
  const probe: Probe | null = picked.probe
  const parts: string[] = []
  if (probe?.duration) parts.push(formatDuration(probe.duration))
  if (probe?.width && probe.height) parts.push(`${probe.width} × ${probe.height}`)
  if (probe?.frameRate) parts.push(`${Number(probe.frameRate.toFixed(2))} fps`)
  if (probe?.videoFormat) parts.push(probe.videoFormat)
  if (probe?.audioFormat) parts.push([probe.audioFormat, probe.sampleRate && `${Number((probe.sampleRate / 1000).toFixed(1))} kHz`].filter(Boolean).join(' '))
  parts.push(formatSize(picked.file.size))
  return parts.join(' · ')
}

// What looks wrong with it as what it's to be, and a full audio that isn't as long as the full video
function concerns(row: Row): string[] {
  const found = warnings(row.picked.probe, row.kind)
  const other: MediaKind | null = row.kind === 'FULL_AUDIO' ? 'FULL_VIDEO' : row.kind === 'FULL_VIDEO' ? 'FULL_AUDIO' : null
  const length = row.picked.probe?.duration
  if (other && length) {
    const otherRow = rows.value.find((it) => it.kind === other)
    const otherLength = otherRow ? otherRow.picked.probe?.duration : current(other)?.info.duration
    if (otherLength && Math.abs(otherLength - length) > 5) {
      found.push(`${formatDuration(Math.abs(otherLength - length))} ${length < otherLength ? 'shorter' : 'longer'} than the ${kindOf(other).label.toLowerCase()}`)
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
  const placement = placeAll(picked, taken)
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
    `${placement.placed.length} added`, placement.refused.length && `${placement.refused.length} refused`,
    leftOut && `${leftOut} other ${leftOut === 1 ? 'file' : 'files'} left out`,
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
  if (!keeping.value) return `Replace and upload ${left}`
  const ones = keeping.value === 1 ? 'one' : 'ones'
  return left ? `Upload ${left}, keep the current ${ones}` : `Keep the current ${ones}`
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
        announcement.value = `${row.picked.name} uploaded`
        emit('changed')
      } catch (error) {
        row.upload = stopped(error) ? null : { state: 'failed', message: uploadError(error) }
        if (row.upload) announcement.value = `${row.picked.name} didn't upload: ${row.upload.message}`
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

onBeforeRouteLeave(() => !busy.value || window.confirm('Video or audio is still being uploaded. Leave, and stop it?'))

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
          <h2 :id="`${id}-title`">Upload video and audio</h2>
          <p class="sub"><span v-if="episode.code" class="code">{{ episode.code }}</span><span lang="sl">{{ episode.title }}</span></p>
        </div>
        <!-- Esc on a keyboard, Cancel on a phone -->
        <button type="button" class="close" aria-label="Close" @click="close()">
          <kbd aria-hidden="true">esc</kbd><span class="cancel" aria-hidden="true">Close</span>
        </button>
      </header>

      <div class="body">
        <div v-if="!rows.length" class="drop">
          <FontAwesomeIcon :icon="faFileVideo" class="big-icon"/>
          <p class="big">
            <span class="desk">{{ choosingFor ? `Drop the ${kindOf(choosingFor).label.toLowerCase()} here` : "Drop the episode's video and audio here" }}</span>
            <span class="touch">{{ choosingFor ? `Choose the ${kindOf(choosingFor).label.toLowerCase()}` : "Choose the episode's video and audio" }}</span>
          </p>
          <p class="small">Video {{ VIDEO_FORMATS }} · audio {{ AUDIO_FORMATS }} · up to {{ formatSize(MAX_MEDIA_BYTES) }} a file</p>
          <div class="buttons">
            <button :id="`${id}-choose`" type="button" class="button" @click="chooseFiles?.click()">
              <FontAwesomeIcon :icon="faFileVideo"/>{{ choosingFor ? `Choose the ${kindOf(choosingFor).label.toLowerCase()}` : 'Choose files' }}
            </button>
            <button v-if="!choosingFor" type="button" class="button" @click="chooseFolder?.click()"><FontAwesomeIcon :icon="faFolderOpen"/>Choose a folder</button>
          </div>
        </div>
        <div v-else class="dropbar">
          <FontAwesomeIcon :icon="faFolderOpen"/>
          <span class="grow">{{ rows.length }} {{ rows.length === 1 ? 'file' : 'files' }}</span>
          <button :id="`${id}-choose`" type="button" class="button small quiet" :disabled="busy" @click="chooseFiles?.click()">Add more…</button>
        </div>
        <p v-if="reading" class="note" role="status">Reading {{ reading === 1 ? 'a file' : `${reading} files` }}…</p>

        <ul v-if="rows.length" class="slots" aria-label="Files to upload">
          <li v-for="row in rows" :key="row.picked.id" class="slot">
            <span class="kind-icon" :class="{ new: ['new', 'replace'].includes(status(row)) }">
              <FontAwesomeIcon :icon="isVideo(row.picked) ? faFileVideo : faFileAudio"/>
            </span>
            <span class="text">
              <span class="name"><b class="file-name">{{ row.picked.name }}</b></span>
              <span class="line">{{ probeLine(row.picked) }}</span>
              <span class="line choose">
                <label :for="`${id}-kind-${row.picked.id}`">As</label>
                <select :id="`${id}-kind-${row.picked.id}`" :value="row.kind" :disabled="busy || !!row.upload"
                        @change="setKind(row, ($event.target as HTMLSelectElement).value)">
                  <option v-for="kind in kindsFor(row)" :key="kind.kind" :value="kind.kind">{{ kind.label }}</option>
                </select>
                <template v-if="!kindOf(row.kind).full">
                  <label class="visually-hidden" :for="`${id}-title-${row.picked.id}`">Title of {{ row.picked.name }}</label>
                  <input :id="`${id}-title-${row.picked.id}`" v-model="row.title" class="title-input" type="text" maxlength="200"
                         placeholder="Title, e.g. what the bonus is" :disabled="busy || !!row.upload" lang="sl">
                </template>
              </span>
              <span v-for="concern in concerns(row)" :key="concern" class="line warn"><FontAwesomeIcon :icon="faTriangleExclamation"/>{{ concern }}</span>
              <span class="line state">
                <template v-if="status(row) === 'new'"><span class="st new"><i/>New</span></template>
                <template v-else-if="status(row) === 'replace'"><span class="st"><FontAwesomeIcon :icon="faRightLeft"/>Replaces</span><span class="file">{{ current(row.kind)!.name }}</span></template>
                <template v-else-if="status(row) === 'waiting'"><span class="st hollow"><i/>Waiting</span><span>after the one before it</span></template>
                <template v-else-if="status(row) === 'uploading'">
                  <span class="st">Uploading {{ percent(row) }}%</span>
                  <span class="progress" role="progressbar" :aria-valuenow="percent(row)" aria-valuemin="0" aria-valuemax="100" :aria-label="row.picked.name"><b :style="{ width: `${percent(row)}%` }"/></span>
                </template>
                <template v-else-if="status(row) === 'checking'"><span class="st"><span class="spin" aria-hidden="true"/>Checking</span><span>reading what's in it</span></template>
                <template v-else-if="status(row) === 'done'"><span class="st ok"><FontAwesomeIcon :icon="faCheck"/>Uploaded</span></template>
                <template v-else-if="status(row) === 'failed'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>Didn't upload</span><span>{{ failure(row) }}</span></template>
              </span>
            </span>
            <span class="actions">
              <button v-if="status(row) === 'failed'" type="button" class="button small" :disabled="busy" @click="retry(row)"><FontAwesomeIcon :icon="faRotateRight"/>Try again</button>
              <button v-if="['new', 'replace', 'failed'].includes(status(row))" type="button" class="iconbutton" :disabled="busy"
                      :aria-label="`Leave ${row.picked.name} out`" @click="leaveOut(row)"><FontAwesomeIcon :icon="faXmark"/></button>
            </span>
          </li>
        </ul>

        <template v-if="refused.length">
          <p class="group">Can't be any of them</p>
          <ul class="slots">
            <li v-for="{ picked, reason } in refused" :key="picked.id" class="slot">
              <span class="kind-icon refused"><FontAwesomeIcon :icon="faCircleExclamation"/></span>
              <span class="text">
                <span class="name"><b class="file-name">{{ picked.name }}</b></span>
                <span class="line state"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>Refused</span><span>{{ reason }}</span></span>
              </span>
              <span class="actions">
                <button type="button" class="iconbutton" :aria-label="`Leave ${picked.name} out`" @click="refused = refused.filter((it) => it.picked.id !== picked.id)"><FontAwesomeIcon :icon="faXmark"/></button>
              </span>
            </li>
          </ul>
        </template>
      </div>

      <div v-if="asking" class="ask" role="group" :aria-labelledby="`${id}-ask`">
        <p :id="`${id}-ask`" class="question" tabindex="-1">
          Replace {{ asking.length === 1 ? `the ${kindOf(asking[0]!.kind).label.toLowerCase()}` : `${asking.length} files` }} {{ episode.code ?? 'the episode' }} already has?
        </p>
        <label v-for="row in asking" :key="row.picked.id" class="replacement" :class="{ on: ticked.includes(row.picked.id) }">
          <input v-model="ticked" type="checkbox" :value="row.picked.id">
          <span>
            <b>{{ kindOf(row.kind).label }}</b>
            <span class="swap">
              <span class="was file">{{ current(row.kind)!.name }}</span>
              <FontAwesomeIcon :icon="faArrowRight" class="arrow"/>
              <span class="becomes file">{{ row.picked.name }}</span>
            </span>
          </span>
        </label>
        <p class="note">The episode's history keeps which file it was. The bucket keeps the one replaced for 30 days, where only an administrator gets it back.</p>
        <div class="buttons end">
          <button type="button" class="button quiet" @click="asking = undefined">Back</button>
          <button type="button" class="button primary" @click="answer()">{{ answerLabel }}</button>
        </div>
      </div>
      <div v-else class="foot">
        <span class="summary" aria-live="polite">{{ summary }}</span>
        <span class="buttons">
          <template v-if="busy"><button type="button" class="button quiet" @click="stop()">Stop</button></template>
          <template v-else-if="finished && !toSend.length">
            <button v-if="count('failed')" type="button" class="button" @click="retry()"><FontAwesomeIcon :icon="faRotateRight"/>Try again</button>
            <button type="button" class="button primary" @click="close()">Done</button>
          </template>
          <template v-else>
            <button type="button" class="button quiet" @click="cancel()">Cancel</button>
            <button type="button" class="button primary" :aria-disabled="!toSend.length || reading > 0" @click="reading || upload()">Upload{{ toSend.length ? ` ${toSend.length}` : '' }}</button>
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
  border-radius: 8px;

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
    background: #FDF0F3;
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
  font-size: 12.5px;
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 6.4px;
}

.warn {
  gap: 6px;
  color: #8A5A00;

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
