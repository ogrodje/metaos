<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, useId } from 'vue'
import { faImages } from '@fortawesome/free-regular-svg-icons'
import {
  faArrowRight, faCheck, faCircleExclamation, faEquals, faFolderOpen, faRightLeft, faRotateRight, faXmark,
} from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import {
  type ArtworkKind, droppedFiles, isPicture, type Picked, placeAll, readPicked, refusal, sizeOf, type Slot, slotOf, SLOTS,
} from '@/artwork'
import type { EpisodeDetails } from '@/episodes'
import { type Image, src } from '@/images'
import { type DropboxCopy, stopped, uploadArtwork, uploadError } from '@/uploads'

// Upload artwork: an episode's five pictures, each PNG or JPEG of exactly its size. Drop the episode's folder or
// pictures anywhere on it, or choose them, and each goes where its size and name say (artwork.ts); or drop or choose
// one on its row. What isn't the size is refused there and then; one that would replace a picture the episode has
// waits for a yes, asked once before anything is sent. Each picture is its own upload, one at a time, so a refused or
// failed one holds nothing back. Closing it doesn't stop what's being sent; Stop does.

const props = defineProps<{
  episode: EpisodeDetails
}>()

const emit = defineEmits<{
  // A picture is the episode's now
  changed: []
}>()

type Upload =
  | { state: 'waiting' }
  | { state: 'uploading', percent: number }
  | { state: 'publishing' }
  | { state: 'done', image: Image, dropbox: DropboxCopy | null }
  | { state: 'failed', message: string }

interface Row {
  // What's going in, and why here: its name, its size, or the user put it here
  picked: Picked | null
  by: 'name' | 'size' | 'chosen' | null
  // What was put here and doesn't fit, and why
  refused: { picked: Picked, reason: string } | null
  upload: Upload | null
  // Whether it may replace the episode's picture, once that was asked
  replace: boolean
}

const id = useId()
const dialog = ref<HTMLDialogElement>()
const choosePictures = ref<HTMLInputElement>()
const chooseFolder = ref<HTMLInputElement>()
const chooseOne = ref<HTMLInputElement>()
// The row Choose… is for
const choosingFor = ref<ArtworkKind>()

const emptyRow = (): Row => ({ picked: null, by: null, refused: null, upload: null, replace: false })
const rows = reactive(Object.fromEntries(SLOTS.map((slot) => [slot.kind, emptyRow()])) as Record<ArtworkKind, Row>)
// Of a size that fits, but nothing said where; and what can't be any of the pictures
const unplaced = ref<Picked[]>([])
const refused = ref<{ picked: Picked, reason: string }[]>([])
// What was dropped or chosen, as the bar above the rows says it
const source = ref<{ name: string | null, pictures: number, leftOut: number }>()
const reading = ref(false)
// While it asks whether to replace, the ones that would, ticked to be replaced
const asking = ref<ArtworkKind[]>()
const ticked = ref<ArtworkKind[]>([])
const busy = ref(false)
const announcement = ref('')
// Files are being dragged over it; counted, as each element they pass over says when they leave it
const dragging = ref(0)
let controller: AbortController | undefined

const current = (slot: Slot): Image | null => props.episode[slot.field]

type Status = 'empty' | 'current' | 'new' | 'replace' | 'same' | 'refused' | Upload['state']

function status(slot: Slot): Status {
  const row = rows[slot.kind]
  if (row.upload) return row.upload.state
  if (row.refused) return 'refused'
  const had = current(slot)
  if (row.picked) return !had ? 'new' : had.sha256 === row.picked.sha256 ? 'same' : 'replace'
  return had ? 'current' : 'empty'
}

const statuses = computed(() => Object.fromEntries(SLOTS.map((slot) => [slot.kind, status(slot)])) as Record<ArtworkKind, Status>)
const toSend = computed(() => SLOTS.filter((slot) => ['new', 'replace'].includes(statuses.value[slot.kind])))
const replacing = computed(() => toSend.value.filter((slot) => statuses.value[slot.kind] === 'replace'))
const count = (state: Status) => SLOTS.filter((slot) => statuses.value[slot.kind] === state).length
const refusedCount = computed(() => count('refused') + refused.value.length)
const sending = computed(() => SLOTS.filter((slot) => rows[slot.kind].upload).length)
const finished = computed(() => !busy.value && SLOTS.some((slot) => ['done', 'failed'].includes(statuses.value[slot.kind])))

const summary = computed(() => {
  const refusedNote = refusedCount.value ? ` · ${refusedCount.value} refused` : ''
  if (busy.value) return `Uploading ${sending.value} ${sending.value === 1 ? 'picture' : 'pictures'} · ${count('done')} uploaded`
  if (finished.value && !toSend.value.length) return [`${count('done')} uploaded`, count('failed') && `${count('failed')} didn't`].filter(Boolean).join(' · ')
  if (!toSend.value.length) return refusedCount.value ? `Nothing to upload${refusedNote}` : 'Nothing chosen yet'
  const n = toSend.value.length
  const r = replacing.value.length
  const replaces = !r ? '' : n === 1 ? ', replacing a picture' : `, ${r} of them replacing ${r === 1 ? 'a picture' : 'pictures'}`
  return `${n} to upload${replaces}${refusedNote}`
})

// What a row shows: the picture going in, the one refused there, or the episode's, as a copy from the public bucket
function preview(slot: Slot): string | null {
  const row = rows[slot.kind]
  if (row.refused) return row.refused.picked.type ? previews.get(row.refused.picked) : null
  if (row.picked) return previews.get(row.picked)
  const had = current(slot)
  return had ? src(had, 320) : null
}

// Its file: the one refused there, going in, or the episode's
const fileName = (slot: Slot) => (rows[slot.kind].refused?.picked ?? rows[slot.kind].picked ?? current(slot))?.name

// Something new is going in, or on its way
const isNew = (slot: Slot) => ['new', 'replace', 'waiting', 'uploading', 'publishing', 'failed'].includes(statuses.value[slot.kind])

function percent(slot: Slot) {
  const upload = rows[slot.kind].upload
  return upload?.state === 'uploading' ? upload.percent : 0
}

function failure(slot: Slot) {
  const upload = rows[slot.kind].upload
  return upload?.state === 'failed' ? upload.message : ''
}

function reset() {
  for (const slot of SLOTS) Object.assign(rows[slot.kind], emptyRow())
  unplaced.value = []
  refused.value = []
  source.value = undefined
  asking.value = undefined
}

/** Opens it; at a row's Choose… when it's for one of the pictures */
function open(kind?: ArtworkKind) {
  if (!dialog.value) return
  // Started afresh once what was sent last time is done
  if (!busy.value && !toSend.value.length) reset()
  if (!dialog.value.open) dialog.value.showModal()
  if (kind) void nextTick(() => document.getElementById(`${id}-choose-${kind}`)?.focus())
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

async function read(files: File[]) {
  reading.value = true
  try {
    const pictures = files.filter(isPicture)
    return { picked: await Promise.all(pictures.map(readPicked)), leftOut: files.length - pictures.length }
  } finally {
    reading.value = false
  }
}

// Pictures dropped or chosen together: each where its name or size says, keeping what's picked already
async function add(files: File[], folder: string | null) {
  if (busy.value || !files.length) return
  const { picked, leftOut } = await read(files)
  const taken = SLOTS.filter((slot) => rows[slot.kind].picked || rows[slot.kind].upload?.state === 'done').map((slot) => slot.kind)
  const placement = placeAll(picked, taken)
  for (const { kind, picked: it, by } of placement.placed) Object.assign(rows[kind], { picked: it, by, refused: null, upload: null })
  unplaced.value.push(...placement.unplaced)
  refused.value.push(...placement.refused)
  source.value = {
    name: folder ?? (source.value ? null : picked.length === 1 ? picked[0]!.name : null),
    pictures: (source.value?.pictures ?? 0) + picked.length,
    leftOut: (source.value?.leftOut ?? 0) + leftOut,
  }
  announcement.value = [
    `${placement.placed.length} placed`, placement.unplaced.length && `${placement.unplaced.length} not placed`,
    placement.refused.length && `${placement.refused.length} refused`,
  ].filter(Boolean).join(', ')
}

// One picture for one row, which it has to fit
async function put(kind: ArtworkKind, file: File) {
  if (busy.value) return
  reading.value = true
  const picked = await readPicked(file).finally(() => (reading.value = false))
  const row = rows[kind]
  const reason = refusal(picked, slotOf(kind))
  // Another one there goes back among those not placed
  if (!reason && row.picked && row.by !== 'chosen') unplaced.value.push(row.picked)
  Object.assign(row, reason ? { refused: { picked, reason }, upload: null } : { picked, by: 'chosen', refused: null, upload: null })
  if (!reason) unplaced.value = unplaced.value.filter((it) => it.id !== picked.id)
  announcement.value = reason ? `${slotOf(kind).label}: refused. ${reason}` : `${slotOf(kind).label}: ${picked.name}`
}

async function onDrop(event: DragEvent) {
  dragging.value = 0
  if (!event.dataTransfer) return
  const { files, folder } = await droppedFiles(event.dataTransfer.items)
  await add(files, folder)
}

async function onRowDrop(kind: ArtworkKind, event: DragEvent) {
  dragging.value = 0
  const files = event.dataTransfer ? (await droppedFiles(event.dataTransfer.items)).files.filter(isPicture) : []
  // More than one is for them all to be placed
  if (files.length === 1) await put(kind, files[0]!)
  else await add(files, null)
}

async function onChosen(event: Event, folder: boolean) {
  const input = event.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  await add(files, folder ? files[0]?.webkitRelativePath.split('/')[0] ?? null : null)
}

async function onChosenOne(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file && choosingFor.value) await put(choosingFor.value, file)
}

function choose(kind: ArtworkKind) {
  choosingFor.value = kind
  chooseOne.value?.click()
}

function leaveOut(kind: ArtworkKind) {
  Object.assign(rows[kind], { picked: null, by: null, refused: null, upload: null })
}

// A picture not placed, put in a row; one there already takes its place among them
function use(picked: Picked, kind: string) {
  const row = rows[kind as ArtworkKind]
  if (!row) return
  unplaced.value = unplaced.value.filter((it) => it.id !== picked.id)
  if (row.picked) unplaced.value.push(row.picked)
  Object.assign(row, { picked, by: 'chosen', refused: null, upload: null })
}

const fitting = (picked: Picked) => SLOTS.filter((slot) => !refusal(picked, slot))

function upload() {
  if (busy.value || !toSend.value.length) return
  if (replacing.value.length) {
    asking.value = replacing.value.map((slot) => slot.kind)
    ticked.value = [...asking.value]
    void nextTick(() => document.getElementById(`${id}-ask`)?.focus())
  } else {
    void send(toSend.value.map((slot) => slot.kind))
  }
}

// After the question: those not ticked stay as they are, and the rest goes
const keeping = computed(() => (asking.value?.length ?? 0) - ticked.value.length)
const answerLabel = computed(() => {
  const left = toSend.value.length - keeping.value
  if (!keeping.value) return `Replace and upload ${left}`
  const ones = keeping.value === 1 ? 'one' : 'ones'
  return left ? `Upload ${left}, keep the current ${ones}` : `Keep the current ${ones}`
})

function answer() {
  const kept = asking.value?.filter((kind) => !ticked.value.includes(kind)) ?? []
  for (const kind of kept) leaveOut(kind)
  for (const kind of ticked.value) rows[kind].replace = true
  asking.value = undefined
  void send(toSend.value.map((slot) => slot.kind))
}

async function send(kinds: ArtworkKind[]) {
  if (!kinds.length) return
  busy.value = true
  controller = new AbortController()
  const { signal } = controller
  for (const kind of kinds) rows[kind].upload = { state: 'waiting' }
  try {
    for (const kind of kinds) {
      const row = rows[kind]
      if (signal.aborted) {
        row.upload = null
        continue
      }
      const label = slotOf(kind).label
      try {
        const uploaded = await uploadArtwork(props.episode.id, kind, row.picked!, row.replace, (progress) => {
          row.upload = progress.state === 'uploading' ? { state: 'uploading', percent: progress.percent } : { state: 'publishing' }
        }, signal)
        row.upload = { state: 'done', image: uploaded.image, dropbox: uploaded.dropbox }
        announcement.value = `${label} uploaded`
        emit('changed')
      } catch (error) {
        row.upload = stopped(error) ? null : { state: 'failed', message: uploadError(error) }
        if (row.upload) announcement.value = `${label} didn't upload: ${row.upload.message}`
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

function retry(kind?: ArtworkKind) {
  const failed = SLOTS.filter((slot) => statuses.value[slot.kind] === 'failed' && (!kind || slot.kind === kind)).map((slot) => slot.kind)
  for (const it of failed) rows[it].upload = null
  void send(failed)
}

// Where it went in Dropbox, after its upload: there already, saved there under the name it has there, or why not
function dropboxNote(row: Row): string | null {
  const copy = row.upload?.state === 'done' ? row.upload.dropbox : null
  if (!copy) return null
  if (copy.error) return `not in Dropbox: ${copy.error.replace(/\.$/, '')}`
  const name = copy.path?.split('/').pop()
  if (!copy.copied) return 'already in Dropbox'
  return name && name !== row.picked?.name ? `saved in Dropbox as ${name}` : 'saved in Dropbox'
}

// Each picked picture's preview, made once and let go with the dialog
const urls = new Map<number, string>()
const previews = {
  get(picked: Picked) {
    if (!urls.has(picked.id)) urls.set(picked.id, URL.createObjectURL(picked.file))
    return urls.get(picked.id)!
  },
}

onBeforeUnmount(() => {
  controller?.abort()
  for (const url of urls.values()) URL.revokeObjectURL(url)
})

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="artwork" :aria-labelledby="`${id}-title`" @click="onDialogClick"
          @dragenter.prevent="dragging++" @dragleave="dragging = Math.max(0, dragging - 1)" @dragover.prevent @drop.prevent="onDrop">
    <div class="panel" :class="{ dragging: dragging > 0 && !busy }">
      <header class="head">
        <div class="names">
          <h2 :id="`${id}-title`">Upload artwork</h2>
          <p class="sub"><span v-if="episode.code" class="code">{{ episode.code }}</span><span lang="sl">{{ episode.title }}</span></p>
        </div>
        <!-- Esc on a keyboard, Cancel on a phone -->
        <button type="button" class="close" aria-label="Close" @click="close()">
          <kbd aria-hidden="true">esc</kbd><span class="cancel" aria-hidden="true">Close</span>
        </button>
      </header>

      <div class="body">
        <div v-if="!source" class="drop">
          <FontAwesomeIcon :icon="faImages" class="big-icon"/>
          <p class="big"><span class="desk">Drop the episode's folder or pictures here</span><span class="touch">Choose the episode's pictures or its folder</span></p>
          <p class="small">PNG or JPEG · thumbnails and web <span class="mono">1920 × 1080</span>, cover <span class="mono">3000 × 3000</span></p>
          <div class="buttons">
            <button type="button" class="button" @click="choosePictures?.click()"><FontAwesomeIcon :icon="faImages"/>Choose pictures</button>
            <button type="button" class="button" @click="chooseFolder?.click()"><FontAwesomeIcon :icon="faFolderOpen"/>Choose a folder</button>
          </div>
        </div>
        <div v-else class="dropbar">
          <FontAwesomeIcon :icon="faFolderOpen"/>
          <span class="grow">
            <b v-if="source.name">{{ source.name }} · </b>{{ source.pictures }} {{ source.pictures === 1 ? 'picture' : 'pictures' }}<template v-if="source.leftOut"> · {{ source.leftOut }} other {{ source.leftOut === 1 ? 'file' : 'files' }} left out</template>
          </span>
          <button type="button" class="button small quiet" :disabled="busy" @click="choosePictures?.click()">Add more…</button>
        </div>
        <p v-if="reading" class="note">Reading the files…</p>

        <ul class="slots" aria-label="The episode's pictures">
          <li v-for="slot in SLOTS" :key="slot.kind" class="slot" @dragover.prevent @drop.prevent.stop="onRowDrop(slot.kind, $event)">
            <span class="pv" :class="{ square: slot.width === slot.height, empty: !preview(slot), new: isNew(slot), refused: !!rows[slot.kind].refused }">
              <img v-if="preview(slot)" :src="preview(slot)!" alt="">
              <span v-else class="ratio">{{ slot.width === slot.height ? '1:1' : '16:9' }}</span>
            </span>
            <span class="text">
              <span class="name"><b>{{ slot.label }}</b><span class="size">{{ sizeOf(slot) }}</span></span>
              <span class="line">
                <template v-if="statuses[slot.kind] === 'empty'"><span class="none">None yet</span></template>
                <span v-else class="file">{{ fileName(slot) }}</span>
              </span>
              <span class="line state" :class="statuses[slot.kind]">
                <template v-if="statuses[slot.kind] === 'empty'">{{ slot.kind === 'WEB' ? 'The thumbnail without its words, for ogrodje.si' : 'Drop a picture on this row, or choose one' }}</template>
                <template v-else-if="statuses[slot.kind] === 'current'"><span class="st hollow"><i/>Current</span></template>
                <template v-else-if="statuses[slot.kind] === 'new'"><span class="st new"><i/>New</span><span>{{ rows[slot.kind].by === 'chosen' ? 'chosen for it' : `placed by its ${rows[slot.kind].by}` }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'replace'"><span class="st"><FontAwesomeIcon :icon="faRightLeft"/>Replaces</span><img class="mini" :src="src(current(slot)!, 320)" alt=""><span class="file">{{ current(slot)!.name }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'same'"><span class="st muted"><FontAwesomeIcon :icon="faEquals"/>Already this picture</span><span>nothing to send</span></template>
                <template v-else-if="statuses[slot.kind] === 'refused'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>Refused</span><span>{{ rows[slot.kind].refused?.reason }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'waiting'"><span class="st hollow"><i/>Waiting</span><span>after the one before it</span></template>
                <template v-else-if="statuses[slot.kind] === 'uploading'">
                  <span class="st">Uploading {{ percent(slot) }}%</span>
                  <span class="progress" role="progressbar" :aria-valuenow="percent(slot)" aria-valuemin="0" aria-valuemax="100" :aria-label="slot.label"><b :style="{ width: `${percent(slot)}%` }"/></span>
                </template>
                <template v-else-if="statuses[slot.kind] === 'publishing'"><span class="st"><span class="spin" aria-hidden="true"/>Making copies</span><span>320 to 1920 px wide</span></template>
                <template v-else-if="statuses[slot.kind] === 'done'"><span class="st ok"><FontAwesomeIcon :icon="faCheck"/>Uploaded</span><span v-if="dropboxNote(rows[slot.kind])">{{ dropboxNote(rows[slot.kind]) }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'failed'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>Didn't upload</span><span>{{ failure(slot) }}</span></template>
              </span>
            </span>
            <span class="actions">
              <button v-if="['empty', 'current'].includes(statuses[slot.kind])" :id="`${id}-choose-${slot.kind}`" type="button" class="button small" :disabled="busy" @click="choose(slot.kind)">Choose…</button>
              <button v-else-if="statuses[slot.kind] === 'refused'" :id="`${id}-choose-${slot.kind}`" type="button" class="button small" :disabled="busy" @click="choose(slot.kind)">Choose another…</button>
              <button v-else-if="statuses[slot.kind] === 'failed'" type="button" class="button small" :disabled="busy" @click="retry(slot.kind)"><FontAwesomeIcon :icon="faRotateRight"/>Try again</button>
              <button v-if="['new', 'replace', 'same'].includes(statuses[slot.kind])" type="button" class="iconbutton" :disabled="busy" :aria-label="`Leave ${rows[slot.kind].picked?.name} out`" @click="leaveOut(slot.kind)"><FontAwesomeIcon :icon="faXmark"/></button>
            </span>
          </li>
        </ul>

        <template v-if="unplaced.length">
          <p class="group">Not placed</p>
          <ul class="slots">
            <li v-for="picked in unplaced" :key="picked.id" class="slot">
              <span class="pv new" :class="{ square: picked.width === picked.height }"><img :src="previews.get(picked)" alt=""></span>
              <span class="text">
                <span class="name"><b class="file-name">{{ picked.name }}</b><span class="size">{{ picked.width }} × {{ picked.height }}</span></span>
                <span class="line state"><span class="st hollow"><i/>Not placed</span><span>its name doesn't say where</span></span>
              </span>
              <span class="actions">
                <label class="visually-hidden" :for="`${id}-use-${picked.id}`">Use {{ picked.name }} as</label>
                <select :id="`${id}-use-${picked.id}`" :disabled="busy" @change="use(picked, ($event.target as HTMLSelectElement).value)">
                  <option value="">Use as…</option>
                  <option v-for="slot in fitting(picked)" :key="slot.kind" :value="slot.kind">{{ slot.label }}</option>
                </select>
                <button type="button" class="iconbutton" :disabled="busy" :aria-label="`Leave ${picked.name} out`" @click="unplaced = unplaced.filter((it) => it.id !== picked.id)"><FontAwesomeIcon :icon="faXmark"/></button>
              </span>
            </li>
          </ul>
        </template>

        <template v-if="refused.length">
          <p class="group">Can't be any of them</p>
          <ul class="slots">
            <li v-for="{ picked, reason } in refused" :key="picked.id" class="slot">
              <span class="pv refused" :class="{ square: picked.width === picked.height, empty: !picked.type }">
                <img v-if="picked.type" :src="previews.get(picked)" alt="">
              </span>
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
          Replace {{ asking.length === 1 ? 'a picture' : `${asking.length} pictures` }} {{ episode.code ?? 'the episode' }} already has?
        </p>
        <label v-for="kind in asking" :key="kind" class="replacement" :class="{ on: ticked.includes(kind) }">
          <input v-model="ticked" type="checkbox" :value="kind">
          <span>
            <b>{{ slotOf(kind).label }}</b>
            <span class="pair" :class="{ square: kind === 'COVER' }">
              <figure class="was"><img :src="src(current(slotOf(kind))!, 640)" :alt="`Current ${slotOf(kind).label.toLowerCase()}`"><figcaption>Current<span class="file">{{ current(slotOf(kind))!.name }}</span></figcaption></figure>
              <FontAwesomeIcon :icon="faArrowRight" class="arrow"/>
              <figure class="becomes"><img :src="previews.get(rows[kind].picked!)" :alt="`New ${slotOf(kind).label.toLowerCase()}`"><figcaption>New<span class="file">{{ rows[kind].picked!.name }}</span></figcaption></figure>
            </span>
          </span>
        </label>
        <p class="note">The episode's history keeps which picture it was.</p>
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
            <button type="button" class="button primary" :aria-disabled="!toSend.length" @click="upload()">Upload{{ toSend.length ? ` ${toSend.length}` : '' }}</button>
          </template>
        </span>
      </div>
      <p class="visually-hidden" aria-live="polite">{{ announcement }}</p>
    </div>

    <input ref="choosePictures" type="file" accept="image/png,image/jpeg" multiple hidden @change="onChosen($event, false)">
    <input ref="chooseFolder" type="file" webkitdirectory hidden @change="onChosen($event, true)">
    <input ref="chooseOne" type="file" accept="image/png,image/jpeg" hidden @change="onChosenOne">
  </dialog>
</template>

<style scoped lang="scss">
kbd {
  display: inline-block;
  min-width: 20px;
  padding: 0 5px;
  border: 1px solid var(--hairline);
  border-radius: 4px;
  background: var(--hover);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
  color: var(--muted);
  text-align: center;
}

.artwork {
  width: min(720px, calc(100vw - 32px));
  max-height: calc(100dvh - 80px);
  margin: 40px auto auto;
  padding: 0;
  border: 0;
  border-radius: 12px;
  background: #FFFFFF;
  color: var(--ink);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, .08), 0 24px 60px -12px rgba(0, 0, 0, .35);
  overflow: hidden;

  &::backdrop {
    background: rgba(20, 20, 20, .38);
  }
}

.panel {
  display: flex;
  flex-direction: column;
  max-height: inherit;

  // Files over it: they may be dropped anywhere on it
  &.dragging {
    outline: 2px dashed var(--accent);
    outline-offset: -6px;
    border-radius: 12px;
  }
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 14px 14px 20px;
  border-bottom: 1px solid var(--hairline);
  flex: none;

  .names {
    min-width: 0;
  }

  h2 {
    font-size: 16px;
    line-height: 24px;
    font-weight: 600;
  }

  .sub {
    display: flex;
    gap: 8px;
    font-size: 13px;
    line-height: 20px;
    color: var(--muted);

    span:last-child {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .code {
    font-family: var(--font-mono);
  }

  .close {
    display: flex;
    align-items: center;
    padding: 6px;
    border: 0;
    border-radius: 6px;
    background: none;
    cursor: pointer;

    .cancel {
      display: none;
      font-size: 15px;
      line-height: 20px;
      font-weight: 500;
      color: var(--accent-text);
    }
  }
}

.body {
  display: grid;
  gap: 4px;
  padding: 16px 20px 8px;
  overflow-y: auto;
  min-height: 0;
}

.drop {
  display: grid;
  justify-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 20px;
  text-align: center;
  background: #FAFAFA;
  border: 1.5px dashed #CFCFCF;
  border-radius: 10px;

  .big-icon {
    width: 22px;
    height: 22px;
    color: var(--muted);
  }

  .big {
    font-size: 14px;
    line-height: 20px;
    font-weight: 500;
  }

  .small {
    font-size: 12.5px;
    line-height: 18px;
    color: var(--muted);
  }

  .touch {
    display: none;
  }
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}

.dropbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  padding: 6px 6px 6px 12px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
  border: 1px solid var(--hairline);
  border-radius: 8px;

  > svg {
    color: var(--muted);
  }

  b {
    font-weight: 500;
    color: var(--ink);
  }

  .grow {
    flex: 1;
    min-width: 0;
  }
}

.note {
  font-size: 12.5px;
  line-height: 18px;
  color: var(--muted);
}

.group {
  padding: 14px 0 2px;
  font-size: 12px;
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);
}

.slots {
  padding: 0;
  list-style: none;
}

.slot {
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
}

.slots:first-of-type .slot:first-child {
  border-top: 0;
}

.pv {
  position: relative;
  display: grid;
  place-items: center;
  width: 128px;
  max-width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: var(--hover);
  border-radius: 6px;

  &.square {
    width: 72px;
    aspect-ratio: 1;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &.empty {
    background: #FFFFFF;
    border: 1.5px dashed #CFCFCF;
  }

  .ratio {
    font-family: var(--font-mono);
    font-size: 11px;
    color: #9A9A9A;
  }

  &.new::after {
    content: 'New';
    position: absolute;
    left: 6px;
    top: 6px;
    padding: 0 5px;
    font: 500 10.5px/16px var(--font-mono);
    color: var(--ink);
    background: rgba(255, 255, 255, .92);
    border-radius: 4px;
  }

  &.refused img {
    opacity: .35;
    filter: grayscale(1);
  }

  &.refused::after {
    content: '';
    position: absolute;
    inset: 0;
    border: 1.5px solid var(--accent-text);
    border-radius: 6px;
  }
}

.text {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.name {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;

  b {
    font-size: 13.5px;
    line-height: 20px;
    font-weight: 600;
  }

  .file-name {
    font-family: var(--font-mono);
    font-size: 12.5px;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
}

.size {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  font-size: 12.5px;
  line-height: 18px;
  color: var(--muted);
}

.file {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--secondary);
  overflow-wrap: anywhere;
}

.state .file {
  font-size: 11.5px;
  color: var(--muted);
}

.none {
  color: var(--muted);
}

.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: var(--ink);

  i {
    width: 7px;
    height: 7px;
    flex: none;
    border-radius: 50%;
    background: var(--ink);
  }

  svg {
    width: 13px;
    height: 13px;
  }

  &.new i {
    background: var(--accent);
  }

  &.hollow,
  &.muted {
    color: var(--muted);
  }

  &.hollow i {
    background: none;
    box-shadow: inset 0 0 0 1.5px var(--muted);
  }

  &.bad {
    color: var(--accent-text);
  }

  &.ok {
    color: #1F7A4D;
  }
}

.mini {
  display: block;
  width: 32px;
  height: 18px;
  object-fit: cover;
  border-radius: 3px;
}

.progress {
  width: 120px;
  max-width: 100%;
  height: 4px;
  overflow: hidden;
  background: var(--rule);
  border-radius: 2px;

  b {
    display: block;
    height: 100%;
    background: var(--ink);
    transition: width .2s;
  }
}

.spin {
  width: 12px;
  height: 12px;
  flex: none;
  border: 1.5px solid var(--hairline);
  border-top-color: var(--ink);
  border-radius: 50%;
  animation: spin .9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
}

select {
  min-height: 32px;
  padding: 0 8px;
  font: inherit;
  font-size: 12.5px;
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 6.4px;
}

.button.small {
  min-height: 32px;
  padding: 0 10px;
  font-size: 12.5px;
}

.button.quiet {
  color: var(--secondary);
}

.button:disabled {
  color: var(--muted);
  cursor: default;
}

// The dialog's one action, filled; said to be unavailable while there's nothing to send
.button.primary {
  color: #FFFFFF;
  background: var(--ink);
  border-color: var(--ink);

  &:hover {
    color: #FFFFFF;
    background: #2B2B2B;
    border-color: #2B2B2B;
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    background: var(--hover);
    border-color: var(--hairline);
    cursor: default;
  }
}

.iconbutton {
  display: grid;
  place-items: center;
  width: var(--icon-link);
  height: var(--icon-link);
  color: var(--muted);
  background: none;
  border: 0;
  border-radius: 8px;
  cursor: pointer;

  &:hover:not(:disabled) {
    color: var(--ink);
    background: var(--hover);
  }

  &:disabled {
    cursor: default;
    opacity: .5;
  }
}

.buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  &.end {
    justify-content: flex-end;
  }
}

.drop .buttons {
  justify-content: center;
  padding-top: 4px;
}

.foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  border-top: 1px solid var(--hairline);
  flex: none;

  .summary {
    font-size: 13px;
    line-height: 20px;
    color: var(--secondary);
  }

  .buttons {
    margin-left: auto;
  }
}

.ask {
  display: grid;
  gap: 12px;
  padding: 16px 20px;
  background: #FAFAFA;
  border-top: 1px solid var(--hairline);
  overflow-y: auto;
  max-height: 60dvh;
  flex: none;

  .question {
    font-size: 14px;
    line-height: 20px;
    font-weight: 600;
    outline: 0;
  }
}

.replacement {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  padding: 12px;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 10px;
  cursor: pointer;

  input {
    width: 16px;
    height: 16px;
    margin: 3px 0 0;
    accent-color: var(--ink);
  }

  b {
    font-size: 13.5px;
    font-weight: 600;
  }

  // What goes, dimmed: the current one while it's ticked, the new one while it isn't
  &.on .was,
  &:not(.on) .becomes {
    opacity: .4;
  }
}

.pair {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-top: 8px;

  figure {
    display: grid;
    gap: 6px;
    min-width: 0;
    margin: 0;
    transition: opacity .15s;
  }

  img {
    display: block;
    width: 100%;
    max-width: 100%;
    aspect-ratio: 16 / 9;
    object-fit: cover;
    border-radius: 6px;
  }

  // Covers side by side as big as thumbnails are high, not as wide
  &.square {
    max-width: 420px;
  }

  &.square img {
    aspect-ratio: 1;
  }

  figcaption {
    display: grid;
    font-size: 12px;
    line-height: 18px;
    color: var(--muted);
  }

  .arrow {
    justify-self: center;
    color: var(--muted);
  }
}

// A phone: the whole screen, and nothing to drag from
@media (max-width: 640px) {
  .artwork {
    width: 100vw;
    max-width: 100vw;
    height: 100dvh;
    max-height: 100dvh;
    margin: 0;
    border-radius: 0;
  }

  .head {
    padding-left: 16px;

    .close {
      kbd {
        display: none;
      }

      .cancel {
        display: inline;
      }
    }
  }

  .body {
    flex: 1;
    padding: 12px 16px 8px;
  }

  .slot {
    grid-template-columns: 88px minmax(0, 1fr);
    align-items: start;
    gap: 4px 12px;
  }

  .pv {
    width: 88px;

    &.square {
      width: 50px;
    }
  }

  .actions {
    grid-column: 2;
    justify-content: flex-start;
    margin-left: -6px;
  }

  .drop {
    .desk {
      display: none;
    }

    .touch {
      display: inline;
    }
  }

  .foot,
  .ask {
    padding: 12px 16px;
  }

  .foot .buttons {
    width: 100%;

    .button {
      flex: 1;
      justify-content: center;
    }
  }

  .pair {
    grid-template-columns: minmax(0, 1fr);

    .arrow {
      transform: rotate(90deg);
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
    border-top-color: var(--muted);
  }

  .progress b,
  .pair figure {
    transition: none;
  }
}
</style>
