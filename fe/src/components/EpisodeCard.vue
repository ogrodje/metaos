<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import ShowBadge from '@/components/ShowBadge.vue'
import { type Episode, episodePath, formatDate, guests } from '@/episodes'
import { src, srcset } from '@/images'

// A released episode in a grid: its thumbnail, or its code on its show's colour without one, then when it aired, its
// title and its guests, or what someone was on it, e.g. as host on a person's page. On phones, a row with a small
// thumbnail.
const props = defineProps<{
  episode: Episode
  role?: string
}>()

const guestNames = computed(() => guests(props.episode).join(', '))
</script>

<template>
  <li class="episode-card">
    <RouterLink class="shot" :to="episodePath(episode)" tabindex="-1" aria-hidden="true">
      <img v-if="episode.thumbnail" :src="src(episode.thumbnail, 640)" :srcset="srcset(episode.thumbnail)"
           sizes="(max-width: 640px) 128px, 280px" :width="episode.thumbnail.width" :height="episode.thumbnail.height"
           alt="" loading="lazy">
      <span v-else class="blank">{{ episode.code }}</span>
    </RouterLink>
    <div class="text">
      <p class="meta">
        <span v-if="episode.code" class="code">{{ episode.code }}</span>
        <time v-if="episode.airedDate" :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time>
        <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
      </p>
      <h3 class="title"><RouterLink :to="episodePath(episode)" lang="sl">{{ episode.title }}</RouterLink></h3>
      <p v-if="role" class="who">{{ role }}</p>
      <p v-else-if="guestNames" class="who" lang="sl">{{ guestNames }}</p>
    </div>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/pictures';

.episode-card {
  display: grid;
  align-content: start;
  gap: 10px;
  min-width: 0;
}

.shot {
  display: block;
  aspect-ratio: 16 / 9;
  @include pictures.picture;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: opacity 0.15s;
  }

  &:hover img {
    opacity: 0.9;
  }
}

// Without a thumbnail: its code, on the grey of a picture that isn't there
.blank {
  display: grid;
  place-items: center;
  height: 100%;
  font-family: var(--font-mono);
  font-size: var(--text-small);
  color: var(--muted);
}

.text {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
}

.code {
  font-family: var(--font-mono);
}

.title {
  font-size: var(--text-body);
  line-height: 21px;
  font-weight: 600;
  letter-spacing: -0.005em;
}

.who {
  font-size: var(--text-small);
  line-height: 19px;
  color: var(--secondary);
}

// A row, with a small thumbnail at the side
@media (max-width: 640px) {
  .episode-card {
    grid-template-columns: 128px minmax(0, 1fr);
    gap: 12px;
    padding: 12px 0;
    border-top: 1px solid var(--rule);
  }


  .title {
    font-size: var(--text-body);
    line-height: 20px;
  }
}
</style>
