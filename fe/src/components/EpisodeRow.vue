<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import FileLinks from '@/components/FileLinks.vue'
import ListenLinks from '@/components/ListenLinks.vue'
import MarkedText from '@/components/MarkedText.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { type Episode, episodePath, formatDate, guests, listPicture } from '@/episodes'
import { src, srcset } from '@/images'
import type { EpisodeMatch } from '@/search'
import { useAuthStore } from '@/stores/auth'

// The title leads to the episode's page, which is the team's while it's in the works
const auth = useAuthStore()

const props = defineProps<{
  episode: Episode
  // Listed under its session's heading, which says when it's recorded unless the episode has a date of its own
  underSession?: boolean
  // Listed under its stage's heading, which says its status
  underStage?: boolean
  // Found by a search: the words to mark, and why it was found when the row doesn't show it
  match?: EpisodeMatch
  // With its picture, its cover or else its thumbnail, e.g. in the list of released episodes
  thumbnail?: boolean
  // What someone was on it, e.g. host on their page, instead of its status
  role?: string
}>()

const released = computed(() => props.episode.status === 'PUBLISHED')
const picture = computed(() => listPicture(props.episode))

// When it aired, once it's out; else when it is or was recorded
const date = computed(() => {
  const { recordingDate, session, airedDate } = props.episode
  if (released.value && airedDate) return airedDate
  if (props.underSession) return recordingDate !== session?.date ? recordingDate : null
  return recordingDate ?? session?.date ?? null
})

// Released, who it's with, as the list doesn't have its summary
const guestNames = computed(() => (released.value ? guests(props.episode).join(', ') : ''))
</script>

<!-- An episode on one line: the code, title and show, then its status (or what someone was on it), when it's recorded
     and its files; released, its guests, when it aired and where to listen, and with its thumbnail where a list wants
     it. Found by a search, under the title what it was found by. EpisodeItem is a fuller row, with the summary. -->
<template>
  <li class="episode" :class="{ thumbnailed: thumbnail }">
    <span class="code" :aria-hidden="!episode.code">{{ episode.code ?? '—' }}</span>
    <span v-if="thumbnail" class="thumbnail" aria-hidden="true">
      <img v-if="picture" :src="src(picture, 320)" :srcset="srcset(picture)" sizes="40px" alt="" loading="lazy">
    </span>
    <div class="main">
      <p class="title">
        <RouterLink v-if="auth.team || released" :to="episodePath(episode)" lang="sl"><MarkedText :text="episode.title" :words="match?.words"/></RouterLink>
        <span v-else lang="sl">{{ episode.title }}</span>
        <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
        <span v-if="guestNames" class="guests" lang="sl">{{ guestNames }}</span>
      </p>
      <p v-if="match?.text" class="found"><span class="label">{{ match.label }}</span> <span lang="sl"><MarkedText :text="match.text" :words="match.words"/></span></p>
    </div>
    <span class="meta">
      <span v-if="role" class="role">{{ role }}</span>
      <StatusLabel v-else-if="!released && !underStage" :status="episode.status"/>
      <span v-else class="none"/>
      <time v-if="date" class="date" :datetime="date">{{ formatDate(date) }}</time>
    </span>
    <span class="files" :class="{ released }">
      <ListenLinks v-if="released" :episode="episode"/>
      <FileLinks v-else :of="episode"/>
    </span>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/episode';
@use '../assets/pictures';

// Status and date in columns of their own, which line up from row to row
.episode {
  display: grid;
  grid-template-columns: episode.$code-width minmax(0, 1fr) 232px 120px;
  align-items: start;
  padding: 11px 0;
}

.title {
  font-size: 14px;
  line-height: 22px;
  font-weight: 500;
  text-wrap: pretty;

  .show {
    margin-left: 10px;
    vertical-align: 1px;
  }

  .guests {
    margin-left: 10px;
    font-size: 13px;
    font-weight: 400;
    color: var(--muted);
  }
}

// A small square picture beside the code, its cover, in a list of released episodes
.thumbnailed {
  grid-template-columns: episode.$code-width 40px minmax(0, 1fr) 232px 120px;
}

.thumbnail {
  display: block;
  aspect-ratio: 1;
  @include pictures.picture($small: true);

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.role {
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
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
  grid-template-columns: 120px minmax(0, 1fr);
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

  .thumbnail {
    display: none;
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

  // Released, only the date, and where to listen is on its page
  .none {
    display: none;
  }

  .none + .date::before {
    content: none;
  }

  .files.released {
    display: none;
  }

  .files {
    grid-area: files;
    align-self: center;
    height: auto;
    margin-left: 4px;
  }
}

</style>
