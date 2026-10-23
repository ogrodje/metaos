<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import FileLinks from '@/components/FileLinks.vue'
import MarkedText from '@/components/MarkedText.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { type Episode, episodePath, formatDate } from '@/episodes'
import type { EpisodeMatch } from '@/search'
import { useAuthStore } from '@/stores/auth'

// The title leads to the episode's page, which is the team's while it's in the works
const auth = useAuthStore()

const props = defineProps<{
  episode: Episode
  // Listed under its session's heading, which says when it's recorded unless the episode has a date of its own
  underSession?: boolean
  // Found by a search: the words to mark, and why it was found when the row doesn't show it
  match?: EpisodeMatch
}>()

// When it is or was recorded
const date = computed(() => {
  const { recordingDate, session } = props.episode
  if (props.underSession) return recordingDate !== session?.date ? recordingDate : null
  return recordingDate ?? session?.date ?? null
})
</script>

<!-- An episode in the works, on one line: the code, title and show, then its status, when it's recorded and its files;
     found by a search, under the title what it was found by. Released episodes have a fuller row of their own,
     EpisodeItem. -->
<template>
  <li class="episode">
    <span class="code" :aria-hidden="!episode.code">{{ episode.code ?? '—' }}</span>
    <div class="main">
      <p class="title">
        <RouterLink v-if="auth.team || episode.status === 'PUBLISHED'" :to="episodePath(episode)" lang="sl"><MarkedText :text="episode.title" :words="match?.words"/></RouterLink>
        <span v-else lang="sl">{{ episode.title }}</span>
        <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
      </p>
      <p v-if="match?.text" class="found"><span class="label">{{ match.label }}</span> <span lang="sl"><MarkedText :text="match.text" :words="match.words"/></span></p>
    </div>
    <span class="meta">
      <StatusLabel :status="episode.status"/>
      <time v-if="date" class="date" :datetime="date">{{ formatDate(date) }}</time>
    </span>
    <span class="files"><FileLinks :of="episode"/></span>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/episode';

// Status and date in columns of their own, which line up from row to row
.episode {
  display: grid;
  grid-template-columns: episode.$code-width minmax(0, 1fr) 248px minmax(96px, auto);
  align-items: start;
  padding: 11px 0;
}

.title {
  font-size: 14px;
  line-height: 22px;
  font-weight: 500;

  .show {
    margin-left: 10px;
    vertical-align: 1px;
  }
}

// Why a search found it
.found {
  margin-top: 2px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);

  .label {
    color: var(--muted);
  }
}

.meta {
  display: grid;
  grid-template-columns: 136px minmax(0, 1fr);
  column-gap: 16px;
  align-items: center;
  height: 22px;
}

.date {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;
}

// As tall as the title's line, which the icon links centre on; the last glyph, not its hit area, lines up with the edge
.files {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  height: 22px;
  margin-right: var(--icon-link-inset);
}

// On phones the status and date go under the title, and the files to the side of both
@media (max-width: 640px) {
  .episode {
    grid-template-columns: episode.$code-width-phone minmax(0, 1fr) auto;
    grid-template-areas: 'code title files' '. meta files';
    row-gap: 2px;
    padding: 10px 0;
  }

  .code {
    grid-area: code;
  }

  .main {
    grid-area: title;
  }

  .meta {
    grid-area: meta;
    display: flex;
    flex-wrap: wrap;
    column-gap: 0;
    height: auto;

    .status, .date {
      font-size: 12px;
      line-height: 18px;
    }
  }

  .date::before {
    content: '·';
    content: '·' / '';
    margin: 0 6px;
  }

  .files {
    grid-area: files;
    align-self: center;
    height: auto;
    margin-left: 4px;
  }
}

</style>
