<script setup lang="ts">
import { computed, ref } from 'vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import type { DropboxPicture, EpisodeDetails, EpisodeStatus, KoofrPhoto } from '@/episodes'
import { type Image, src, srcset } from '@/images'

// An episode's pictures in one section. The team gets tabs: its titles with the thumbnails they're A/B tested with and
// its cover, then its artwork (its Dropbox folders' pictures, each marked with what it's used as) and its photos (its
// Koofr folders'). Everyone else gets its thumbnail, large, as everyone may see it.
const props = defineProps<{
  episode: EpisodeDetails
  artwork: DropboxPicture[]
  photos: KoofrPhoto[]
  team: boolean
}>()

type Tab = 'titles' | 'artwork' | 'photos'
const tab = ref<Tab>('titles')

const tabs = computed(() => [
  { id: 'titles' as Tab, title: 'Titles and thumbnails', count: all.value.filter((v) => v.title).length },
  ...(props.artwork.length ? [{ id: 'artwork' as Tab, title: 'Artwork', count: props.artwork.length }] : []),
  ...(props.photos.length ? [{ id: 'photos' as Tab, title: 'Photos', count: props.photos.length }] : []),
])
// A tab that's gone, e.g. as the artwork's folder was unlinked, leaves the titles
const shown = computed(() => (tabs.value.some((t) => t.id === tab.value) ? tab.value : 'titles'))

interface Variant {
  letter: string
  title: string | null
  image: Image | null
}

const all = computed<Variant[]>(() => {
  const { episode } = props
  return [
    { letter: 'A', title: episode.title, image: episode.thumbnail },
    { letter: 'B', title: episode.titleB, image: episode.thumbnailB },
    { letter: 'C', title: episode.titleC, image: episode.thumbnailC },
  ]
})

// Pictures come once it's recorded: before, a missing one isn't missing yet
const RECORDED: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS', 'PUBLISHED']
const pictured = computed(() => RECORDED.includes(props.episode.status) || all.value.some((v) => v.image) || !!props.episode.cover)

// Once it's recorded, all three, so what's missing shows; before, A, and B and C once they have a title
const variants = computed(() => (pictured.value ? all.value : all.value.filter((v) => v.letter === 'A' || v.title)))

// What each of the artwork's files is used as, by the name of the file a picture was made from
const uses = computed(() => {
  const { episode } = props
  const used: Record<string, string> = {}
  for (const [image, use] of [[episode.cover, 'Cover'], [episode.thumbnailC, 'Thumbnail C'], [episode.thumbnailB, 'Thumbnail B'], [episode.thumbnail, 'Thumbnail A']] as const) {
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
    </div>

    <template v-if="shown === 'titles'">
      <!-- Each title with the thumbnail it's tested with, then the cover -->
      <ul v-if="pictured" class="variants">
        <li v-for="variant in variants" :key="variant.letter">
          <a v-if="variant.image" class="shot" :href="variant.image.url" target="_blank" rel="noopener"
             :title="`${variant.image.name}, ${variant.image.width} × ${variant.image.height}`">
            <img :src="src(variant.image, 640)" :srcset="srcset(variant.image)" sizes="(max-width: 640px) 50vw, 260px"
                 :width="variant.image.width" :height="variant.image.height" :alt="`Thumbnail ${variant.letter}`" loading="lazy">
            <span class="letter" aria-hidden="true">{{ variant.letter }}</span>
          </a>
          <span v-else class="shot missing"><span class="letter" aria-hidden="true">{{ variant.letter }}</span>No thumbnail yet</span>
          <p v-if="variant.title" class="title" lang="sl"><span class="visually-hidden">Title {{ variant.letter }}: </span>{{ variant.title }}</p>
          <p v-else class="title none">No title {{ variant.letter }}</p>
          <p v-if="variant.image" class="file">{{ variant.image.name }}</p>
        </li>
        <li class="cover">
          <a v-if="episode.cover" class="shot" :href="episode.cover.url" target="_blank" rel="noopener"
             :title="`${episode.cover.name}, ${episode.cover.width} × ${episode.cover.height}`">
            <img :src="src(episode.cover, 640)" :srcset="srcset(episode.cover)" sizes="(max-width: 640px) 50vw, 150px"
                 :width="episode.cover.width" :height="episode.cover.height" alt="Cover" loading="lazy">
          </a>
          <span v-else class="shot missing">No cover yet</span>
          <p class="title">Cover</p>
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

    <PhotoStrip v-else-if="shown === 'artwork'" title="Artwork" :photos="artwork" :folder="folder" service="Dropbox"
                artwork :uses="uses" bare/>
    <PhotoStrip v-else title="Photos" :photos="photos" :folder="episode.koofrFolders[0]?.url" service="Koofr" bare/>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.heading {
  flex-wrap: wrap;
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
  overflow: hidden;
  border-radius: 10px;
  background: var(--hover);

  img {
    display: block;
    width: 100%;
    height: auto;
    aspect-ratio: 16 / 9;
    object-fit: cover;
  }
}

// Up to three thumbnails and then the cover, which is square, so as tall as they are
.variants {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) minmax(0, 0.5625fr);


  gap: 16px 14px;
  align-items: start;
  padding: 0;
  list-style: none;

}

.shot {
  position: relative;
  display: block;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  background: var(--hover);

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .cover & {
    aspect-ratio: 1;
  }

  &.missing {
    display: grid;
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
  }

  .tabs {
    margin-left: 0;
  }
}
</style>
