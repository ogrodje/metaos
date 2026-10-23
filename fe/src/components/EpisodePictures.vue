<script setup lang="ts">
import { computed, ref } from 'vue'
import { faArrowUpFromBracket } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { type ArtworkKind, sizeOf, SLOTS } from '@/artwork'
import ArtworkUpload from '@/components/ArtworkUpload.vue'
import type { EpisodeDetails } from '@/episodes'
import { type Image, src, srcset } from '@/images'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  episode: EpisodeDetails
}>()

const emit = defineEmits<{
  // One of its pictures was uploaded
  changed: []
}>()

const auth = useAuthStore()
const upload = ref<InstanceType<typeof ArtworkUpload>>()

interface Picture {
  kind: 'thumbnail' | 'web' | 'cover'
  // A thumbnail's letter in the A/B test, with the title it goes with
  variant?: string
  title?: string | null
  image: Image
}

// The thumbnails by the titles they go with, the web picture, then the cover
const pictures = computed<Picture[]>(() => {
  const { episode } = props
  const all: (Picture | null)[] = [
    episode.thumbnail && { kind: 'thumbnail', variant: 'A', title: episode.title, image: episode.thumbnail },
    episode.thumbnailB && { kind: 'thumbnail', variant: 'B', title: episode.titleB, image: episode.thumbnailB },
    episode.thumbnailC && { kind: 'thumbnail', variant: 'C', title: episode.titleC, image: episode.thumbnailC },
    episode.web && { kind: 'web', image: episode.web },
    episode.cover && { kind: 'cover', image: episode.cover },
  ]
  return all.filter((picture): picture is Picture => picture !== null)
})

// The letters of the thumbnails, by the titles they go with
const VARIANTS: Partial<Record<ArtworkKind, 'A' | 'B' | 'C'>> = { THUMBNAIL: 'A', THUMBNAIL_B: 'B', THUMBNAIL_C: 'C' }

// For the team, an episode with none has their places, each with its size and the title it goes with
const places = computed(() => SLOTS.map((slot) => {
  const variant = VARIANTS[slot.kind]
  const title = variant === 'A' ? props.episode.title : variant === 'B' ? props.episode.titleB : variant === 'C' ? props.episode.titleC : null
  return { ...slot, variant, title }
}))

function open(kind?: ArtworkKind) {
  upload.value?.open(kind)
}
</script>

<!-- An episode's thumbnails, web picture and cover, as everyone sees them from media.ogrodje.si; each opens the
     original. Only the letters say which goes with which title when there's only one thumbnail. The team uploads them
     here (ArtworkUpload), and sees an episode without any have their places. -->
<template>
  <section v-if="pictures.length || auth.team" aria-labelledby="pictures">
    <div class="heading">
      <h2 id="pictures">Pictures</h2>
      <span class="count">{{ pictures.length || 'None yet' }}</span>
      <button v-if="auth.team" type="button" class="button upload" aria-haspopup="dialog" @click="open()">
        <FontAwesomeIcon :icon="faArrowUpFromBracket"/>Upload artwork
      </button>
    </div>
    <ul v-if="pictures.length" class="pictures">
      <li v-for="picture in pictures" :key="picture.variant ?? picture.kind" :class="picture.kind">
        <a :href="picture.image.url" target="_blank" rel="noopener" :title="`${picture.image.name}, ${picture.image.width} × ${picture.image.height}`">
          <img
            :src="src(picture.image, 640)"
            :srcset="srcset(picture.image)"
            sizes="(max-width: 640px) calc(100vw - 32px), 280px"
            :width="picture.image.width"
            :height="picture.image.height"
            :alt="picture.kind === 'cover' ? 'Cover' : picture.kind === 'web' ? 'Web picture' : `Thumbnail ${picture.variant}`"
            loading="lazy"
          >
        </a>
        <p class="caption">
          <template v-if="picture.kind === 'cover'">Cover</template>
          <template v-else-if="picture.kind === 'web'">Web</template>
          <template v-else><span class="variant">{{ picture.variant }}</span><span lang="sl">{{ picture.title ?? 'Thumbnail' }}</span></template>
        </p>
      </li>
    </ul>
    <ul v-else class="pictures">
      <li v-for="place in places" :key="place.kind" :class="place.kind === 'COVER' ? 'cover' : 'thumbnail'">
        <button type="button" class="place" :aria-label="`Upload the ${place.label.toLowerCase()}, ${sizeOf(place)}`" @click="open(place.kind)">
          <FontAwesomeIcon :icon="faArrowUpFromBracket"/>
          <span class="size">{{ sizeOf(place) }}</span>
          <small v-if="place.kind === 'WEB'">without words</small>
        </button>
        <p class="caption">
          <template v-if="!place.variant">{{ place.kind === 'COVER' ? 'Cover' : 'Web' }}</template>
          <template v-else>
            <span class="variant">{{ place.variant }}</span>
            <span v-if="place.title" lang="sl">{{ place.title }}</span><span v-else class="untitled">No title {{ place.variant }} yet</span>
          </template>
        </p>
      </li>
    </ul>
    <ArtworkUpload v-if="auth.team" ref="upload" :episode="episode" @changed="emit('changed')"/>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.heading {
  align-items: center;
}

.upload {
  margin-left: auto;
  min-height: 36px;
  color: var(--secondary);

  svg {
    color: var(--muted);
  }
}

.pictures {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 24px 16px;
  align-items: start;
  padding: 0;
  list-style: none;
}

a {
  display: block;
  border-radius: 8px;
  overflow: hidden;
  background: var(--hover);
}

img {
  display: block;
  width: 100%;
  height: auto;
  object-fit: cover;
}

.thumbnail img,
.web img {
  aspect-ratio: 16 / 9;
}

.cover img {
  aspect-ratio: 1;
}

// A place for a picture the episode hasn't got, which opens Upload artwork at it
.place {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 2px;
  width: 100%;
  aspect-ratio: 16 / 9;
  font: inherit;
  color: var(--muted);
  background: #FFFFFF;
  border: 1.5px dashed #CFCFCF;
  border-radius: 8px;
  cursor: pointer;

  &:hover {
    color: var(--ink);
    border-color: var(--secondary);
  }

  .size {
    font-family: var(--font-mono);
    font-size: 12px;
  }

  small {
    font-size: 12px;
    color: #9A9A9A;
  }
}

.cover .place {
  aspect-ratio: 1;
}

.caption {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
}

.variant {
  font-family: var(--font-mono);
  color: var(--muted);
}

.untitled {
  color: var(--muted);
}

@media (max-width: 640px) {
  .pictures {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
