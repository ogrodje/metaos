<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { faArrowLeft, faArrowRight, faArrowUpRightFromSquare } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { DropboxPicture, KoofrPhoto } from '@/episodes'
import { fetchObjectUrl } from '@/graphql'

// Two rows of four
const PAGE = 8

// A section of photos, a page at a time, with a link that opens [folder] in [service] by its title. Artwork is shown
// whole, as it comes in all shapes (covers, thumbnails, stories), and by name, as its file names say what each is for,
// and with what each is used as, e.g. Thumbnail A, when [uses] says so. Bare, it's without its heading, e.g. in a tab of
// EpisodePictures, which names it.
const props = defineProps<{
  title: string
  photos: (KoofrPhoto | DropboxPicture)[]
  folder?: string
  service: 'Koofr' | 'Dropbox'
  artwork?: boolean
  uses?: Record<string, string>
  bare?: boolean
}>()

// For the section's label: photos, artwork
const headingId = computed(() => props.title.toLowerCase().replace(/\W+/g, '-'))

const page = ref(0)
// Which way the photos slide: in from the right for the next page
const direction = ref<'next' | 'previous'>('next')
const pages = computed(() => Math.ceil(props.photos.length / PAGE))
const shown = computed(() => props.photos.slice(page.value * PAGE, (page.value + 1) * PAGE))
// Empty places on the last page keep the section as tall as the others, so what's under it stays put
const fillers = computed(() => (pages.value > 1 ? PAGE - shown.value.length : 0))

function go(to: number) {
  direction.value = to > page.value ? 'next' : 'previous'
  page.value = to
}

// An <img> can't send the sign-in token, so each thumbnail is fetched with it and shown from memory, by its path. Only
// those of the page and the next one, which is then there right away.
const sources = ref<Record<string, string>>({})
const requested = new Set<string>()

function release() {
  Object.values(sources.value).forEach((url) => URL.revokeObjectURL(url))
  sources.value = {}
  requested.clear()
}

function fetchThumbnails(photos: (KoofrPhoto | DropboxPicture)[]) {
  for (const photo of photos.filter((photo) => !requested.has(photo.thumbnail))) {
    requested.add(photo.thumbnail)
    fetchObjectUrl(photo.thumbnail).then((url) => {
      // Photos that changed meanwhile don't need it any more
      if (props.photos.includes(photo)) sources.value = { ...sources.value, [photo.thumbnail]: url }
      else URL.revokeObjectURL(url)
    }).catch((error) => {
      requested.delete(photo.thumbnail)
      console.error(`No thumbnail of ${photo.path}`, error)
    })
  }
}

watch(() => props.photos, () => {
  release()
  page.value = 0
}, { immediate: true })

watch([() => props.photos, page], ([photos, at]) => fetchThumbnails(photos.slice(at * PAGE, (at + 2) * PAGE)), { immediate: true })

onBeforeUnmount(release)
</script>

<template>
  <section :aria-labelledby="bare ? undefined : headingId" :aria-label="bare ? title : undefined">
    <div v-if="!bare || folder || pages > 1" class="heading" :class="{ bare }">
      <template v-if="!bare">
        <h2 :id="headingId">{{ title }}</h2>
        <span class="count">{{ photos.length }}</span>
      </template>
      <a v-if="folder" class="open" :href="folder" target="_blank" rel="noopener">
        Open on {{ service }} <FontAwesomeIcon :icon="faArrowUpRightFromSquare"/>
      </a>
      <div v-if="pages > 1" class="pager">
        <span class="count" aria-live="polite">{{ page * PAGE + 1 }}–{{ page * PAGE + shown.length }}</span>
        <button type="button" :disabled="page === 0" :aria-label="`Previous ${title.toLowerCase()}`" @click="go(page - 1)">
          <FontAwesomeIcon :icon="faArrowLeft"/>
        </button>
        <button type="button" :disabled="page === pages - 1" :aria-label="`Next ${title.toLowerCase()}`" @click="go(page + 1)">
          <FontAwesomeIcon :icon="faArrowRight"/>
        </button>
      </div>
    </div>

    <!-- A grid of thumbnails, each opening its folder -->
    <Transition :name="direction" mode="out-in">
      <ul :key="page" class="photos" :class="{ artwork }">
        <li v-for="photo in shown" :key="photo.thumbnail">
          <a :href="photo.url" target="_blank" rel="noopener" :title="photo.name">
            <span class="tile">
              <!-- Artwork's name is under it, so it isn't read twice -->
              <img v-if="sources[photo.thumbnail]" :src="sources[photo.thumbnail]" :alt="artwork ? '' : photo.name">
            </span>
            <span v-if="artwork" class="name">{{ photo.name }}</span>
            <span v-if="artwork && uses" class="use" :class="{ unused: !uses[photo.name] }">{{ uses[photo.name] ?? 'Not used' }}</span>
          </a>
        </li>
        <li v-for="filler in fillers" :key="`filler-${filler}`" class="filler" aria-hidden="true">
          <span class="tile"></span>
        </li>
      </ul>
    </Transition>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';
@use '../assets/pictures';

.heading {
  flex-wrap: wrap;
}

.open {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);

  svg {
    font-size: var(--text-micro);
  }
}

// At the heading's end; its buttons are taller than its text, but don't make it taller
.pager {
  display: flex;
  align-items: center;
  gap: 4px;
  align-self: center;
  margin: calc((24px - var(--icon-link)) / 2) 0 calc((24px - var(--icon-link)) / 2) auto;

  .count {
    margin-right: 8px;
    font-variant-numeric: tabular-nums;
  }

  button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: var(--icon-link);
    height: var(--icon-link);
    font-size: var(--text-small);
    color: var(--ink);
    background: #FFFFFF;
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    cursor: pointer;

    &:hover:not(:disabled) {
      border-color: var(--hairline-strong);
    }

    &:disabled {
      color: var(--muted);
      opacity: 0.5;
      cursor: default;
    }
  }
}

.photos {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: start;
  gap: 8px;
  padding: 0;
  list-style: none;

  a {
    display: block;
  }

  .tile {
    display: block;
    aspect-ratio: 3 / 2;
    @include pictures.picture;
  }

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: opacity 0.15s;
  }

  a:hover img {
    opacity: 0.9;
  }

  .filler .tile {
    visibility: hidden;
  }
}

// Whole on its tile, and named under it; a long name wraps rather than losing its end
.artwork {
  row-gap: 16px;

  img {
    object-fit: contain;
  }

  .name {
    display: block;
    margin-top: 6px;
    font-family: var(--font-mono);
    font-size: var(--text-caption);
    line-height: 16px;
    color: var(--secondary);
    overflow-wrap: anywhere;
  }

  a:hover .name {
    color: var(--accent-text);
  }

  // What it's used as: a thumbnail, the cover; or not used, e.g. a thumbnail not set as B or C yet
  .use {
    display: block;
    font-size: var(--text-caption);
    line-height: 16px;
    font-weight: 500;
    color: var(--ink);

    &.unused {
      font-weight: 400;
      color: var(--muted);
    }
  }
}

// In a tab that names it: only the link and the pager, smaller
.heading.bare {
  padding-bottom: 8px;
}

// A page slides out one way and the next one in from the other
.next-enter-active, .next-leave-active, .previous-enter-active, .previous-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.next-enter-from, .previous-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

.next-leave-to, .previous-enter-from {
  opacity: 0;
  transform: translateX(-24px);
}

@media (prefers-reduced-motion: reduce) {
  .next-enter-active, .next-leave-active, .previous-enter-active, .previous-leave-active {
    transition: none;
  }
}

@media (max-width: 640px) {
  .photos {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

</style>
