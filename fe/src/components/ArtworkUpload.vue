<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, useId } from 'vue'
import { faImages } from '@fortawesome/free-regular-svg-icons'
import {
  faArrowRight, faCheck, faCircleExclamation, faEquals, faFolderOpen, faRightLeft, faRotateRight, faXmark,
} from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import {
  type ArtworkKind, droppedFiles, isPicture, type Picked, placeAll, readPicked, refusal, sizeOf, type Slot, slotLabel, slotName,
  slotOf, SLOTS,
} from '@/artwork'
import type { EpisodeDetails } from '@/episodes'
import { type Image, src } from '@/images'
import { type DropboxCopy, stopped, uploadArtwork, uploadError } from '@/uploads'
import { useLocale } from '@/useLocale'

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

const { t } = useLocale()
const label = (kind: ArtworkKind) => slotLabel(kind, t)

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
  const refusedNote = refusedCount.value ? t('media.upload.refusedCount', { n: refusedCount.value }) : null
  const line = (...parts: (string | null | false)[]) => parts.filter(Boolean).join(' · ')
  const done = count('done')
  const failed = count('failed')
  if (busy.value) return t('pictures.upload.summary.busy', { pictures: t('pictures.upload.sending', { n: sending.value }, sending.value), done })
  if (finished.value && !toSend.value.length) return line(t('media.upload.uploadedCount', { n: done }), !!failed && t('media.upload.failedCount', { n: failed }))
  if (!toSend.value.length) return refusedNote ? line(t('media.upload.nothingToUpload'), refusedNote) : t('media.upload.nothingChosen')
  const n = toSend.value.length
  const r = replacing.value.length
  const toUpload = !r
    ? t('media.upload.toUpload', { n })
    : n === 1 ? t('pictures.upload.summary.replacingOne') : t('pictures.upload.summary.replacingSome', { n, r }, r)
  return line(toUpload, refusedNote)
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
  const placement = placeAll(picked, t, taken)
  for (const { kind, picked: it, by } of placement.placed) Object.assign(rows[kind], { picked: it, by, refused: null, upload: null })
  unplaced.value.push(...placement.unplaced)
  refused.value.push(...placement.refused)
  source.value = {
    name: folder ?? (source.value ? null : picked.length === 1 ? picked[0]!.name : null),
    pictures: (source.value?.pictures ?? 0) + picked.length,
    leftOut: (source.value?.leftOut ?? 0) + leftOut,
  }
  announcement.value = [
    t('pictures.upload.announce.placed', { n: placement.placed.length }),
    placement.unplaced.length && t('pictures.upload.announce.unplaced', { n: placement.unplaced.length }),
    placement.refused.length && t('pictures.upload.announce.refused', { n: placement.refused.length }),
  ].filter(Boolean).join(', ')
}

// One picture for one row, which it has to fit
async function put(kind: ArtworkKind, file: File) {
  if (busy.value) return
  reading.value = true
  const picked = await readPicked(file).finally(() => (reading.value = false))
  const row = rows[kind]
  const reason = refusal(picked, slotOf(kind), t)
  // Another one there goes back among those not placed
  if (!reason && row.picked && row.by !== 'chosen') unplaced.value.push(row.picked)
  Object.assign(row, reason ? { refused: { picked, reason }, upload: null } : { picked, by: 'chosen', refused: null, upload: null })
  if (!reason) unplaced.value = unplaced.value.filter((it) => it.id !== picked.id)
  announcement.value = reason
    ? t('pictures.upload.announce.refusedOne', { label: label(kind), reason })
    : `${label(kind)}: ${picked.name}`
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

const fitting = (picked: Picked) => SLOTS.filter((slot) => !refusal(picked, slot, t))

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
  const kept = keeping.value
  if (!kept) return t('media.upload.answer.replace', { n: left })
  return left ? t('media.upload.answer.uploadAndKeep', { n: left, kept }, kept) : t('media.upload.answer.keep', { kept }, kept)
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
      const name = label(kind)
      try {
        const uploaded = await uploadArtwork(props.episode.id, kind, row.picked!, row.replace, (progress) => {
          row.upload = progress.state === 'uploading' ? { state: 'uploading', percent: progress.percent } : { state: 'publishing' }
        }, signal)
        row.upload = { state: 'done', image: uploaded.image, dropbox: uploaded.dropbox }
        announcement.value = t('pictures.upload.announce.uploaded', { label: name })
        emit('changed')
      } catch (error) {
        row.upload = stopped(error) ? null : { state: 'failed', message: uploadError(error, t) }
        if (row.upload) announcement.value = t('pictures.upload.announce.failed', { label: name, message: row.upload.message })
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
  if (copy.error) return t('pictures.upload.dropbox.error', { error: copy.error.replace(/\.$/, '') })
  const name = copy.path?.split('/').pop()
  if (!copy.copied) return t('pictures.upload.dropbox.there')
  return name && name !== row.picked?.name ? t('pictures.upload.dropbox.savedAs', { name }) : t('pictures.upload.dropbox.saved')
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
  <dialog ref="dialog" class="upload-dialog" :aria-labelledby="`${id}-title`" @click="onDialogClick"
          @dragenter.prevent="dragging++" @dragleave="dragging = Math.max(0, dragging - 1)" @dragover.prevent @drop.prevent="onDrop">
    <div class="panel" :class="{ dragging: dragging > 0 && !busy }">
      <header class="head">
        <div class="names">
          <h2 :id="`${id}-title`">{{ t('pictures.upload.title') }}</h2>
          <p class="sub"><span v-if="episode.code" class="code">{{ episode.code }}</span><span lang="sl">{{ episode.title }}</span></p>
        </div>
        <!-- Esc on a keyboard, Cancel on a phone -->
        <button type="button" class="close" :aria-label="t('common.close')" @click="close()">
          <kbd aria-hidden="true">esc</kbd><span class="cancel" aria-hidden="true">{{ t('common.close') }}</span>
        </button>
      </header>

      <div class="body">
        <div v-if="!source" class="drop">
          <FontAwesomeIcon :icon="faImages" class="big-icon"/>
          <p class="big"><span class="desk">{{ t('pictures.upload.drop') }}</span><span class="touch">{{ t('pictures.upload.dropTouch') }}</span></p>
          <i18n-t keypath="pictures.upload.formats" tag="p" class="small" scope="global">
            <template #wide><span class="mono">1920 × 1080</span></template>
            <template #square><span class="mono">3000 × 3000</span></template>
          </i18n-t>
          <div class="buttons">
            <button type="button" class="button" @click="choosePictures?.click()"><FontAwesomeIcon :icon="faImages"/>{{ t('pictures.upload.choose') }}</button>
            <button type="button" class="button" @click="chooseFolder?.click()"><FontAwesomeIcon :icon="faFolderOpen"/>{{ t('media.upload.chooseFolder') }}</button>
          </div>
        </div>
        <div v-else class="dropbar">
          <FontAwesomeIcon :icon="faFolderOpen"/>
          <span class="grow">
            <b v-if="source.name">{{ source.name }} · </b>{{ t('pictures.upload.pictures', { n: source.pictures }, source.pictures) }}<template v-if="source.leftOut"> · {{ t('media.upload.leftOut', { n: source.leftOut }, source.leftOut) }}</template>
          </span>
          <button type="button" class="button small quiet" :disabled="busy" @click="choosePictures?.click()">{{ t('media.upload.addMore') }}</button>
        </div>
        <p v-if="reading" class="note">{{ t('pictures.upload.reading') }}</p>

        <ul class="slots" :aria-label="t('pictures.upload.slots')">
          <li v-for="slot in SLOTS" :key="slot.kind" class="slot" @dragover.prevent @drop.prevent.stop="onRowDrop(slot.kind, $event)">
            <span class="pv" :class="{ square: slot.width === slot.height, empty: !preview(slot), new: isNew(slot), refused: !!rows[slot.kind].refused }">
              <img v-if="preview(slot)" :src="preview(slot)!" alt="">
              <span v-else class="ratio">{{ slot.width === slot.height ? '1:1' : '16:9' }}</span>
            </span>
            <span class="text">
              <span class="name"><b>{{ label(slot.kind) }}</b><span class="size">{{ sizeOf(slot) }}</span></span>
              <span class="line">
                <template v-if="statuses[slot.kind] === 'empty'"><span class="none">{{ t('pictures.upload.none') }}</span></template>
                <span v-else class="file">{{ fileName(slot) }}</span>
              </span>
              <span class="line state" :class="statuses[slot.kind]">
                <template v-if="statuses[slot.kind] === 'empty'">{{ slot.kind === 'WEB' ? t('pictures.upload.webHint') : t('pictures.upload.rowHint') }}</template>
                <template v-else-if="statuses[slot.kind] === 'current'"><span class="st hollow"><i/>{{ t('pictures.upload.current') }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'new'"><span class="st new"><i/>{{ t('pictures.upload.new') }}</span><span>{{ rows[slot.kind].by === 'chosen' ? t('pictures.upload.chosen') : rows[slot.kind].by === 'size' ? t('pictures.upload.bySize') : t('pictures.upload.byName') }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'replace'"><span class="st"><FontAwesomeIcon :icon="faRightLeft"/>{{ t('media.upload.replaces') }}</span><img class="mini" :src="src(current(slot)!, 320)" alt=""><span class="file">{{ current(slot)!.name }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'same'"><span class="st muted"><FontAwesomeIcon :icon="faEquals"/>{{ t('pictures.upload.same') }}</span><span>{{ t('pictures.upload.nothingToSend') }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'refused'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>{{ t('media.upload.refused') }}</span><span>{{ rows[slot.kind].refused?.reason }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'waiting'"><span class="st hollow"><i/>{{ t('media.upload.waiting') }}</span><span>{{ t('media.upload.afterPrevious') }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'uploading'">
                  <span class="st">{{ t('media.upload.uploading', { percent: percent(slot) }) }}</span>
                  <span class="progress" role="progressbar" :aria-valuenow="percent(slot)" aria-valuemin="0" aria-valuemax="100" :aria-label="label(slot.kind)"><b :style="{ width: `${percent(slot)}%` }"/></span>
                </template>
                <template v-else-if="statuses[slot.kind] === 'publishing'"><span class="st"><span class="spin" aria-hidden="true"/>{{ t('pictures.upload.publishing') }}</span><span>{{ t('pictures.upload.copies') }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'done'"><span class="st ok"><FontAwesomeIcon :icon="faCheck"/>{{ t('media.upload.uploaded') }}</span><span v-if="dropboxNote(rows[slot.kind])">{{ dropboxNote(rows[slot.kind]) }}</span></template>
                <template v-else-if="statuses[slot.kind] === 'failed'"><span class="st bad"><FontAwesomeIcon :icon="faCircleExclamation"/>{{ t('media.upload.failed') }}</span><span>{{ failure(slot) }}</span></template>
              </span>
            </span>
            <span class="actions">
              <button v-if="['empty', 'current'].includes(statuses[slot.kind])" :id="`${id}-choose-${slot.kind}`" type="button" class="button small" :disabled="busy" @click="choose(slot.kind)">{{ t('pictures.upload.chooseOne') }}</button>
              <button v-else-if="statuses[slot.kind] === 'refused'" :id="`${id}-choose-${slot.kind}`" type="button" class="button small" :disabled="busy" @click="choose(slot.kind)">{{ t('pictures.upload.chooseAnother') }}</button>
              <button v-else-if="statuses[slot.kind] === 'failed'" type="button" class="button small" :disabled="busy" @click="retry(slot.kind)"><FontAwesomeIcon :icon="faRotateRight"/>{{ t('common.tryAgain') }}</button>
              <button v-if="['new', 'replace', 'same'].includes(statuses[slot.kind])" type="button" class="iconbutton" :disabled="busy" :aria-label="t('media.upload.leaveOut', { name: rows[slot.kind].picked?.name })" @click="leaveOut(slot.kind)"><FontAwesomeIcon :icon="faXmark"/></button>
            </span>
          </li>
        </ul>

        <template v-if="unplaced.length">
          <p class="group">{{ t('pictures.upload.unplaced') }}</p>
          <ul class="slots">
            <li v-for="picked in unplaced" :key="picked.id" class="slot">
              <span class="pv new" :class="{ square: picked.width === picked.height }"><img :src="previews.get(picked)" alt=""></span>
              <span class="text">
                <span class="name"><b class="file-name">{{ picked.name }}</b><span class="size">{{ picked.width }} × {{ picked.height }}</span></span>
                <span class="line state"><span class="st hollow"><i/>{{ t('pictures.upload.unplacedOne') }}</span><span>{{ t('pictures.upload.unplacedWhy') }}</span></span>
              </span>
              <span class="actions">
                <label class="visually-hidden" :for="`${id}-use-${picked.id}`">{{ t('pictures.upload.useAs', { name: picked.name }) }}</label>
                <select :id="`${id}-use-${picked.id}`" :disabled="busy" @change="use(picked, ($event.target as HTMLSelectElement).value)">
                  <option value="">{{ t('pictures.upload.useAsPrompt') }}</option>
                  <option v-for="slot in fitting(picked)" :key="slot.kind" :value="slot.kind">{{ label(slot.kind) }}</option>
                </select>
                <button type="button" class="iconbutton" :disabled="busy" :aria-label="t('media.upload.leaveOut', { name: picked.name })" @click="unplaced = unplaced.filter((it) => it.id !== picked.id)"><FontAwesomeIcon :icon="faXmark"/></button>
              </span>
            </li>
          </ul>
        </template>

        <template v-if="refused.length">
          <p class="group">{{ t('media.upload.fitsNone') }}</p>
          <ul class="slots">
            <li v-for="{ picked, reason } in refused" :key="picked.id" class="slot">
              <span class="pv refused" :class="{ square: picked.width === picked.height, empty: !picked.type }">
                <img v-if="picked.type" :src="previews.get(picked)" alt="">
              </span>
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
          {{ t('pictures.upload.ask', { n: asking.length, episode: episode.code ?? t('media.upload.theEpisode') }, asking.length) }}
        </p>
        <label v-for="kind in asking" :key="kind" class="replacement" :class="{ on: ticked.includes(kind) }">
          <input v-model="ticked" type="checkbox" :value="kind">
          <span>
            <b>{{ label(kind) }}</b>
            <span class="pair" :class="{ square: kind === 'COVER' }">
              <figure class="was"><img :src="src(current(slotOf(kind))!, 640)" :alt="t('pictures.upload.currentOf', { name: slotName(kind, t) })"><figcaption>{{ t('pictures.upload.current') }}<span class="file">{{ current(slotOf(kind))!.name }}</span></figcaption></figure>
              <FontAwesomeIcon :icon="faArrowRight" class="arrow"/>
              <figure class="becomes"><img :src="previews.get(rows[kind].picked!)" :alt="t('pictures.upload.newOf', { name: slotName(kind, t) })"><figcaption>{{ t('pictures.upload.new') }}<span class="file">{{ rows[kind].picked!.name }}</span></figcaption></figure>
            </span>
          </span>
        </label>
        <p class="note">{{ t('pictures.upload.history') }}</p>
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
            <button type="button" class="button primary" :aria-disabled="!toSend.length" @click="upload()">{{ toSend.length ? t('media.upload.uploadN', { n: toSend.length }) : t('media.upload.upload') }}</button>
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
@use '../assets/pictures';
@use '../assets/upload-dialog';

.pv {
  position: relative;
  display: grid;
  place-items: center;
  width: 128px;
  max-width: 100%;
  aspect-ratio: 16 / 9;
  @include pictures.picture($small: true);

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
    outline: 0;
    background: #FFFFFF;
    border: 1.5px dashed var(--faint);
  }

  .ratio {
    font-family: var(--font-mono);
    font-size: var(--text-micro);
    color: var(--muted);
  }

  &.new::after {
    content: 'New';
    position: absolute;
    left: 6px;
    top: 6px;
    padding: 0 5px;
    font: 500 var(--text-micro)/16px var(--font-mono);
    color: var(--ink);
    background: rgba(255, 255, 255, .92);
    border-radius: var(--radius-xs);
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
    border-radius: var(--radius-sm);
  }
}

.mini {
  display: block;
  width: 32px;
  height: 18px;
  object-fit: cover;
  border-radius: var(--radius-xs);
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
    border-radius: var(--radius-sm);
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
    font-size: var(--text-caption);
    line-height: 18px;
    color: var(--muted);
  }

  .arrow {
    justify-self: center;
    color: var(--muted);
  }
}

// A phone
@media (max-width: 640px) {
  .pv {
    width: 88px;

    &.square {
      width: 50px;
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
  .pair figure {
    transition: none;
  }
}
</style>
