<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { faArrowUpFromBracket, faChevronRight, faLock } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import DocLinks from '@/components/DocLinks.vue'
import DropboxFolderList from '@/components/DropboxFolderList.vue'
import EpisodePictures from '@/components/EpisodePictures.vue'
import EpisodeTranscript from '@/components/EpisodeTranscript.vue'
import Fact from '@/components/Fact.vue'
import KoofrFolderList from '@/components/KoofrFolderList.vue'
import MediaList from '@/components/MediaList.vue'
import MediaUpload from '@/components/MediaUpload.vue'
import { type DropboxPicture, type EpisodeDetails, isRecorded, type KoofrPhoto, type LinePart, pictureParts } from '@/episodes'
import { type MediaKind, mediaParts } from '@/media'
import { transcriptsLine } from '@/transcriptions'

// What the episode is made of, at the end of its page and the team's only: its transcript, its pictures, and its video
// and audio, each folded to a line that says what there is and what's missing, until it's wanted. Each once there's
// something to it: the pictures once it's recorded or has some, or titles to test; the video and audio once it's
// recorded; the transcript once there's a file to transcribe; none of it, e.g. for an idea, until then. While it's in
// the works, what's missing is the work left, so a part that misses something is open. A part loads what it shows once
// it's opened, e.g. the transcript's text; a link to one (#transcript, #pictures, #media) opens it. Last, its files: its
// docs and its Koofr and Dropbox folders, not folded, as their links are what they're for.
const props = defineProps<{
  episode: EpisodeDetails
  artwork: DropboxPicture[]
  photos: KoofrPhoto[]
}>()

const emit = defineEmits<{
  // Something was uploaded or removed
  changed: []
}>()

type Part = 'transcript' | 'pictures' | 'media'

// Those opened so far, which stay as they are while they're closed again
const opened = ref<Partial<Record<Part, boolean>>>({})
const parts = ref<Partial<Record<Part, HTMLDetailsElement>>>({})

function toggled(part: Part) {
  if (parts.value[part]?.open) opened.value = { ...opened.value, [part]: true }
}

// Opening it opens what it shows, as toggled says
function openLinked(hash: string) {
  const part = parts.value[hash.slice(1) as Part]
  if (part) part.open = true
}

const recorded = computed(() => isRecorded(props.episode.status))
const released = computed(() => props.episode.status === 'PUBLISHED')
const transcribed = computed(() => props.episode.transcripts.length > 0)

const lines = computed<Record<Part, LinePart[]>>(() => ({
  transcript: [{ text: transcriptsLine(props.episode.transcripts) }],
  pictures: pictureParts(props.episode, props.artwork.length, props.photos.length),
  media: mediaParts(props.episode.media, recorded.value),
}))

const shown = computed<Record<Part | 'files', boolean>>(() => {
  const { episode } = props
  return {
    files: episode.documents.length > 0 || episode.koofrFolders.length > 0 || episode.dropboxFolders.length > 0,
    transcript: transcribed.value || episode.media.length > 0,
    pictures: recorded.value || lines.value.pictures.length > 0,
    media: recorded.value || episode.media.length > 0,
  }
})

const route = useRoute()
onMounted(() => {
  if (!released.value) {
    for (const part of ['pictures', 'media'] as const) {
      if (lines.value[part].some((line) => line.missing) && parts.value[part]) parts.value[part].open = true
    }
  }
  openLinked(route.hash)
})
watch(() => route.hash, openLinked)

const mediaUpload = ref<InstanceType<typeof MediaUpload>>()

function upload(kind?: MediaKind) {
  mediaUpload.value?.open(kind)
}
</script>

<template>
  <!-- Released, the page is anyone's but for this part; in the works, all of it is the team's -->
  <section v-if="Object.values(shown).some(Boolean)" class="behind" aria-labelledby="behind">
    <div class="heading" :class="{ released }">
      <h2 id="behind">Behind the episode</h2>
      <FontAwesomeIcon v-if="released" class="lock" :icon="faLock"/>
    </div>
    <p v-if="released" class="note intro">Only the team sees this part.</p>

    <details v-if="shown.transcript" id="transcript" :ref="(el) => { parts.transcript = el as HTMLDetailsElement }"
             @toggle="toggled('transcript')">
      <summary>
        <span class="part">Transcript</span>
        <span class="line">{{ lines.transcript[0]!.text }}</span>
        <FontAwesomeIcon class="chevron" :icon="faChevronRight"/>
      </summary>
      <div class="inside">
        <EpisodeTranscript v-if="transcribed && opened.transcript" :transcripts="episode.transcripts" :media="episode.media"
                           :youtube-url="episode.youtubeUrl"/>
        <p v-else-if="!transcribed" class="note">
          Not transcribed yet: <RouterLink to="/transcriptions">transcribe it</RouterLink> on the Transcriptions page.
        </p>
      </div>
    </details>

    <details v-if="shown.pictures" id="pictures" :ref="(el) => { parts.pictures = el as HTMLDetailsElement }"
             @toggle="toggled('pictures')">
      <summary>
        <span class="part">Pictures</span>
        <span class="line">
          <template v-for="(part, i) in lines.pictures" :key="part.text"><template v-if="i"> · </template><span
            :class="{ missing: part.missing }">{{ part.text }}</span></template>
        </span>
        <FontAwesomeIcon class="chevron" :icon="faChevronRight"/>
      </summary>
      <div class="inside">
        <EpisodePictures v-if="opened.pictures" :episode="episode" :artwork="artwork" :photos="photos" @changed="emit('changed')"/>
      </div>
    </details>

    <details v-if="shown.media" id="media" :ref="(el) => { parts.media = el as HTMLDetailsElement }" @toggle="toggled('media')">
      <summary>
        <span class="part">Video and audio</span>
        <span class="line">
          <template v-if="!lines.media.length">None yet</template>
          <template v-for="(part, i) in lines.media" :key="part.text"><template v-if="i"> · </template><span
            :class="{ missing: part.missing }">{{ part.text }}</span></template>
        </span>
        <FontAwesomeIcon class="chevron" :icon="faChevronRight"/>
      </summary>
      <div class="inside">
        <MediaList :media="episode.media" @upload="upload" @changed="emit('changed')"/>
        <button type="button" class="button" aria-haspopup="dialog" @click="upload()">
          <FontAwesomeIcon :icon="faArrowUpFromBracket"/>Upload video and audio
        </button>
      </div>
    </details>

    <!-- Links, which are what it's for, so in view rather than folded -->
    <div v-if="shown.files" id="files" class="files">
      <h3 class="part">Files</h3>
      <dl>
        <Fact v-if="episode.documents.length" label="Docs"><span class="icons"><DocLinks :documents="episode.documents"/></span></Fact>
        <Fact v-if="episode.koofrFolders.length" label="Koofr"><KoofrFolderList :folders="episode.koofrFolders"/></Fact>
        <Fact v-if="episode.dropboxFolders.length" label="Dropbox"><DropboxFolderList :folders="episode.dropboxFolders"/></Fact>
      </dl>
    </div>

    <MediaUpload ref="mediaUpload" :episode="episode" @changed="emit('changed')"/>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.heading {
  align-items: center;

  // With the note under it
  &.released {
    padding-bottom: 4px;
  }
}

.lock {
  width: 12px;
  height: 12px;
  color: var(--muted);
}

.intro {
  margin-bottom: 12px;
}

// The parts between Hairlines, the last one's under it too
details, .files {
  border-top: 1px solid var(--hairline);
}

.files, details:last-of-type:not(:has(~ .files)) {
  border-bottom: 1px solid var(--hairline);
}

// Files, in the folded parts' columns, with what's in them in view
.files {
  display: grid;
  grid-template-columns: 136px minmax(0, 1fr) 16px;
  align-items: baseline;
  column-gap: 16px;
  padding: 14px 0;

  h3 {
    font-size: var(--text-body);
    line-height: 22px;
  }

  dl {
    display: grid;
    gap: 8px;
    font-size: var(--text-small);
    line-height: 20px;
  }
}

// The doc icons' glyphs, not their hit areas, line up with the text
.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

// A part, what it has in a line, and which way it opens
summary {
  display: grid;
  grid-template-columns: 136px minmax(0, 1fr) 16px;
  align-items: baseline;
  column-gap: 16px;
  padding: 14px 0;
  list-style: none;
  cursor: pointer;

  &::-webkit-details-marker {
    display: none;
  }

  &:hover .part {
    text-decoration: underline;
    text-decoration-color: var(--faint);
    text-underline-offset: 3px;
  }

  &:focus-visible {
    outline-offset: -2px;
    border-radius: var(--radius-sm);
  }
}

.part {
  font-weight: 600;
}

.line {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

// What it's missing, once it's recorded
.missing {
  color: var(--accent-text);
}

.chevron {
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--muted);
  transition: transform 0.15s ease-out;

  [open] > summary & {
    transform: rotate(90deg);
  }

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
}

.inside {
  display: grid;
  gap: 16px;
  padding: 4px 0 28px;

  > .button {
    justify-self: start;
  }

  .note a {
    font-weight: 500;
    color: var(--accent-text);
  }
}

@media (max-width: 640px) {
  summary {
    grid-template-columns: minmax(0, 1fr) 16px;

    .line {
      grid-row: 2;
    }
  }

  .files {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 8px;
  }
}
</style>
