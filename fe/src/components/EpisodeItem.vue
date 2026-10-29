<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import ListenLinks from '@/components/ListenLinks.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import MarkedText from '@/components/MarkedText.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import { type Episode, episodePath, formatDate, guests } from '@/episodes'
import type { EpisodeMatch } from '@/search'

const props = defineProps<{
  episode: Episode
  // Found by a search: the words to mark, and why it was found when the row doesn't show it
  match?: EpisodeMatch
}>()

const guestNames = computed(() => guests(props.episode).join(', '))
</script>

<!-- A released episode: the code, then its title, which leads to its page, tiny summary, when it aired and its guests, then where to listen.
     The episodes in the works have a row of their own, EpisodeRow. Found by a search, what it was found by. -->
<template>
  <li class="episode">
    <span class="code" :aria-hidden="!episode.code">{{ episode.code ?? '—' }}</span>
    <div class="main">
      <p class="title">
        <RouterLink :to="episodePath(episode)" lang="sl"><MarkedText :text="episode.title" :words="match?.words"/></RouterLink>
        <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
      </p>
      <MarkdownText v-if="episode.tinySummary" class="summary" :source="episode.tinySummary" lang="sl"/>
      <p v-if="match?.text" class="found"><span class="label">{{ match.label }}</span> <span lang="sl"><MarkedText :text="match.text" :words="match.words"/></span></p>
      <p v-if="episode.airedDate || guestNames" class="details">
        <time v-if="episode.airedDate" :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time>
        <span v-if="guestNames" class="guests"><MarkedText :text="guestNames" :words="match?.words"/></span>
      </p>
    </div>
    <div v-if="episode.youtubeUrl || episode.spotifyUrl || episode.applePodcastsUrl || episode.patreonUrl" class="links">
      <ListenLinks :episode="episode"/>
    </div>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/episode';

.episode {
  display: grid;
  grid-template-columns: episode.$code-width minmax(0, 1fr) auto;
  padding: 18px 0;
}

@include episode.summary-row;

// On the title's line
.code {
  line-height: 24px;
}

// Why a search found it
.found {
  margin-top: 4px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);

  .label {
    color: var(--muted);
  }
}

// On phones the code goes above the title, so the text has the whole width
@media (max-width: 640px) {
  .episode {
    grid-template-columns: minmax(0, 1fr);
    padding: 16px 0 12px;
  }

  .code {
    font-size: var(--text-caption);
    line-height: 18px;
  }

  .title {
    margin-top: 2px;
  }
}

</style>
