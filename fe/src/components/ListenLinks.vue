<script setup lang="ts">
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { brands } from '@/brands'
import { type Episode, links } from '@/episodes'

// Where to listen to an episode, each in its brand's colour when pointed at: icons in a list, or buttons that also say
// where, e.g. on its page
defineProps<{
  episode: Pick<Episode, 'youtubeUrl' | 'spotifyUrl' | 'applePodcastsUrl' | 'patreonUrl'>
  labelled?: boolean
}>()
</script>

<template>
  <template v-if="labelled">
    <a v-for="link in links(episode)" :key="link.platform" class="button listen" :href="link.url"
       :style="{ '--brand': brands[link.platform].color }">
      <FontAwesomeIcon :icon="brands[link.platform].icon"/>{{ link.platform }}
    </a>
  </template>
  <template v-else>
    <a v-for="link in links(episode)" :key="link.platform" class="icon-link brand-link" :href="link.url"
       :title="link.platform" :aria-label="link.platform" :style="{ '--brand': brands[link.platform].color }">
      <FontAwesomeIcon :icon="brands[link.platform].icon"/>
    </a>
  </template>
</template>

<style scoped lang="scss">

// A button that says where, its icon in the brand's colour
.listen {
  min-height: 36px;
  padding: 0 10px;
  white-space: nowrap;

  svg {
    color: var(--brand);
  }
}
</style>
