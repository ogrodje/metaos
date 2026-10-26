<script setup lang="ts">
import { computed, ref } from 'vue'
import { faArrowUpFromBracket } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { ArtworkKind } from '@/artwork'
import ArtworkUpload from '@/components/ArtworkUpload.vue'
import MediaList from '@/components/MediaList.vue'
import MediaUpload from '@/components/MediaUpload.vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import type { DropboxPicture, EpisodeDetails, EpisodeStatus, KoofrPhoto } from '@/episodes'
import { type Image, src, srcset } from '@/images'
import type { MediaKind } from '@/media'

// An episode's pictures in one section. The team gets tabs: its titles with the thumbnails they're A/B tested with, its
// web picture and its cover, its video and audio (MediaList), then its artwork (its Dropbox folders' pictures, each
// marked with what it's used as) and its photos (its Koofr folders'); and uploads them (ArtworkUpload, MediaUpload, by
// the tab), from the heading, or a missing one from its place. Everyone else gets its thumbnail, large, as everyone may
// see it.
const props = defineProps<{
  episode: EpisodeDetails
  artwork: DropboxPicture[]
  photos: KoofrPhoto[]
  team: boolean
}>()

const emit = defineEmits<{
  // One of its pictures was uploaded
  changed: []
}>()

const upload = ref<InstanceType<typeof ArtworkUpload>>()
const mediaUpload = ref<InstanceType<typeof MediaUpload>>()

function open(kind?: ArtworkKind) {
  upload.value?.open(kind)
}

function openMedia(kind?: MediaKind) {
  mediaUpload.value?.open(kind)
}

type Tab = 'titles' | 'media' | 'artwork' | 'photos'
const tab = ref<Tab>('titles')

const tabs = computed(() => [
  { id: 'titles' as Tab, title: 'Titles and thumbnails', count: all.value.filter((v) => v.title).length },
  ...(recorded.value || props.episode.media.length ? [{ id: 'media' as Tab, title: 'Video and audio', count: props.episode.media.length }] : []),
  ...(props.artwork.length ? [{ id: 'artwork' as Tab, title: 'Artwork', count: props.artwork.length }] : []),
  ...(props.photos.length ? [{ id: 'photos' as Tab, title: 'Photos', count: props.photos.length }] : []),
])
// A tab that's gone, e.g. as the artwork's folder was unlinked, leaves the titles
const shown = computed(() => (tabs.value.some((t) => t.id === tab.value) ? tab.value : 'titles'))

interface Variant {
  kind: ArtworkKind
  letter: string
  title: string | null
  image: Image | null
}

const all = computed<Variant[]>(() => {
  const { episode } = props
  return [
    { kind: 'THUMBNAIL', letter: 'A', title: episode.title, image: episode.thumbnail },
    { kind: 'THUMBNAIL_B', letter: 'B', title: episode.titleB, image: episode.thumbnailB },
    { kind: 'THUMBNAIL_C', letter: 'C', title: episode.titleC, image: episode.thumbnailC },
  ]
})

// Pictures, video and audio come once it's recorded: before, a missing one isn't missing yet
const RECORDED: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS', 'PUBLISHED']
const recorded = computed(() => RECORDED.includes(props.episode.status))
const pictured = computed(() => recorded.value || all.value.some((v) => v.image) || !!props.episode.cover || !!props.episode.web)

// Once it's recorded, all three, so what's missing shows; before, A, and B and C once they have a title
const variants = computed(() => (pictured.value ? all.value : all.value.filter((v) => v.letter === 'A' || v.title)))

// What each of the artwork's files is used as, by the name of the file a picture was made from
const uses = computed(() => {
  const { episode } = props
  const used: Record<string, string> = {}
  const pictures = [
    [episode.cover, 'Cover'], [episode.web, 'Web picture'], [episode.thumbnailC, 'Thumbnail C'],
    [episode.thumbnailB, 'Thumbnail B'], [episode.thumbnail, 'Thumbnail A'],
  ] as const
  for (const [image, use] of pictures) {
    if (image) used[image.name] = used[image.name] ? `${use}, ${used[image.name]}` : use
  }
  return used
})

const folder = computed(() => props.artwork[0]?.url)
</script>

<template>
  <!-- Everyone: the thumbnail, opening the original -->
  <a v-if="!team && episode.thumbnail" class="hero" :href="episode.thumbnail.url" target="_blank" rel="noopener"
     :title="`${episode.thumbnail.name}, ${episode.thumbnail.width} × ${episode.thumbnail.height}`">
    <img :src="src(episode.thumbnail, 1280)" :srcset="srcset(episode.thumbnail)" sizes="(max-width: 960px) 100vw, 840px"
         :width="episode.thumbnail.width" :height="episode.thumbnail.height" alt="Thumbnail">
  </a>

  <!-- An idea with only its title has none to show but the page's heading -->
  <section v-else-if="team && (pictured || variants.length > 1 || tabs.length > 1)" aria-labelledby="media">
    <div class="heading">
      <h2 id="media">Media</h2>
      <div v-if="tabs.length > 1" class="tabs" role="group" aria-label="Show">
        <button v-for="t in tabs" :key="t.id" type="button" :aria-pressed="shown === t.id" @click="tab = t.id">
          {{ t.title }}<span class="count">{{ t.count }}</span>
        </button>
      </div>
      <button v-if="shown === 'media'" type="button" class="button upload" aria-haspopup="dialog" @click="openMedia()">
        <FontAwesomeIcon :icon="faArrowUpFromBracket"/>Upload video and audio
      </button>
      <button v-else type="button" class="button upload" aria-haspopup="dialog" @click="open()">
        <FontAwesomeIcon :icon="faArrowUpFromBracket"/>Upload artwork
      </button>
    </div>

    <template v-if="shown === 'titles'">
      <!-- The cover, large, then each title with the thumbnail it's tested with, and the web picture -->
      <ul v-if="pictured" class="variants">
        <li class="cover">
          <a v-if="episode.cover" class="shot" :href="episode.cover.url" target="_blank" rel="noopener"
             :title="`${episode.cover.name}, ${episode.cover.width} × ${episode.cover.height}`">
            <img :src="src(episode.cover, 640)" :srcset="srcset(episode.cover)" sizes="(max-width: 640px) 50vw, 260px"
                 :width="episode.cover.width" :height="episode.cover.height" alt="Cover" loading="lazy">
          </a>
          <button v-else type="button" class="shot missing" aria-haspopup="dialog" @click="open('COVER')">Upload the cover</button>
          <p class="title">Cover</p>
        </li>
        <li v-for="variant in variants" :key="variant.letter">
          <a v-if="variant.image" class="shot" :href="variant.image.url" target="_blank" rel="noopener"
             :title="`${variant.image.name}, ${variant.image.width} × ${variant.image.height}`">
            <img :src="src(variant.image, 640)" :srcset="srcset(variant.image)" sizes="(max-width: 640px) 50vw, 300px"
                 :width="variant.image.width" :height="variant.image.height" :alt="`Thumbnail ${variant.letter}`" loading="lazy">
            <span class="letter" aria-hidden="true">{{ variant.letter }}</span>
          </a>
          <button v-else type="button" class="shot missing" aria-haspopup="dialog" @click="open(variant.kind)">
            <span class="letter" aria-hidden="true">{{ variant.letter }}</span>Upload thumbnail {{ variant.letter }}
          </button>
          <p v-if="variant.title" class="title" lang="sl"><span class="visually-hidden">Title {{ variant.letter }}: </span>{{ variant.title }}</p>
          <p v-else class="title none">No title {{ variant.letter }}</p>
          <p v-if="variant.image" class="file">{{ variant.image.name }}</p>
        </li>
        <li class="web">
          <a v-if="episode.web" class="shot" :href="episode.web.url" target="_blank" rel="noopener"
             :title="`${episode.web.name}, ${episode.web.width} × ${episode.web.height}`">
            <img :src="src(episode.web, 640)" :srcset="srcset(episode.web)" sizes="(max-width: 640px) 50vw, 260px"
                 :width="episode.web.width" :height="episode.web.height" alt="Web picture" loading="lazy">
          </a>
          <button v-else type="button" class="shot missing" aria-haspopup="dialog" @click="open('WEB')">Upload the web picture</button>
          <p class="title">Web picture</p>
          <p v-if="episode.web" class="file">{{ episode.web.name }}</p>
        </li>
      </ul>
      <!-- Before the recording, the titles only -->
      <ul v-else class="titles" lang="sl">
        <li v-for="variant in variants" :key="variant.letter">
          <span class="letter" aria-hidden="true">{{ variant.letter }}</span>
          <span v-if="variant.title"><span class="visually-hidden">Title {{ variant.letter }}: </span>{{ variant.title }}</span>
          <span v-else class="none">No title {{ variant.letter }}</span>
        </li>
      </ul>
    </template>

    <MediaList v-else-if="shown === 'media'" :media="episode.media" @upload="openMedia" @changed="emit('changed')"/>
    <PhotoStrip v-else-if="shown === 'artwork'" title="Artwork" :photos="artwork" :folder="folder" service="Dropbox"
                artwork :uses="uses" bare/>
    <PhotoStrip v-else title="Photos" :photos="photos" :folder="episode.koofrFolders[0]?.url" service="Koofr" bare/>

    <ArtworkUpload ref="upload" :episode="episode" @changed="emit('changed')"/>
    <MediaUpload ref="mediaUpload" :episode="episode" @changed="emit('changed')"/>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';
@use '../assets/pictures';

.heading {
  flex-wrap: wrap;
}

// Upload artwork, at the heading's end
.upload {
  min-height: var(--icon-link);
  margin-left: 8px;
  padding: 0 10px;
}

// Which of the pictures, at the heading's end
.tabs {
  display: flex;
  gap: 2px;
  max-width: 100%;
  margin-left: auto;
  padding: 3px;
  overflow-x: auto;
  scrollbar-width: none;
  background: var(--hover);
  border-radius: 8px;

  button {
    flex: none;
    padding: 4px 10px;
    font-size: 13px;
    line-height: 20px;
    font-weight: 500;
    color: var(--secondary);
    background: none;
    border: 0;
    border-radius: 6px;
    cursor: pointer;

    &[aria-pressed="true"] {
      color: var(--ink);
      background: #FFFFFF;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
    }

    &:focus-visible {
      outline-offset: -2px;
    }
  }

  .count {
    margin-left: 6px;
  }
}

.hero {
  display: block;
  @include pictures.picture;

  img {
    display: block;
    width: 100%;
    height: auto;
    aspect-ratio: 16 / 9;
    object-fit: cover;
  }
}

// The cover, square, as tall as it's wide, then the thumbnails and the web picture in two rows of two beside it
.variants {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) repeat(2, minmax(0, 1fr));

  .cover {
    grid-row: span 2;
  }


  gap: 16px 14px;
  align-items: start;
  padding: 0;
  list-style: none;

}

.shot {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  @include pictures.picture;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .cover & {
    aspect-ratio: 1;
  }

  // A place to upload one: dashed, rather than the edge of a picture
  &.missing {
    outline: 0;
    display: grid;
    width: 100%;
    font: inherit;
    cursor: pointer;
    place-items: center;
    padding: 8px;
    font-size: 12px;
    line-height: 16px;
    color: var(--muted);
    text-align: center;
    background: #FCFCFC;
    border: 1px dashed #D4D4D4;
  }
}

.letter {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 0 6px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
  font-weight: 500;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.94);
  border-radius: 4px;
}

.title {
  margin-top: 8px;
  font-size: 13px;
  line-height: 19px;
  font-weight: 500;
}

.none {
  font-weight: 400;
  color: var(--muted);
}

.file {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 16px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

// An idea's titles, each after its letter
.titles {
  display: grid;
  gap: 6px;
  padding: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 20px minmax(0, 1fr);
    align-items: baseline;
  }

  .letter {
    position: static;
    padding: 0;
    font-size: 13px;
    color: var(--muted);
    background: none;
  }
}

@media (max-width: 640px) {
  .variants {
    grid-template-columns: repeat(2, minmax(0, 1fr));

    .cover {
      grid-row: auto;
    }
  }

  .tabs {
    margin-left: 0;
  }
}
</style>
