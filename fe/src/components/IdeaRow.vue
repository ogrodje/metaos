<script setup lang="ts">
import { computed } from 'vue'
import { I18nT } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import FileLinks from '@/components/FileLinks.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import { candidates, episodePath, formatDate, guests, type Idea, sessionPath } from '@/episodes'
import { personPath } from '@/people'
import { useLocale } from '@/useLocale'

// An idea in a list, a row as an episode in the works is on the Episodes page (EpisodeRow): its title and show; who it's
// with, its guests, or who might be (its candidates), who recommended it and when it was added, in a line; its tiny
// summary; then when it's to be recorded and its files, in columns of their own. On phones, a block of lines.
const props = defineProps<{ idea: Idea }>()

const { t, dateLocale, path } = useLocale()

const guestNames = computed(() => guests(props.idea).join(', '))
const candidateNames = computed(() => candidates(props.idea).join(', '))
// Whether the line starts with who it's with, so what follows is in lower case
const people = computed(() => !!guestNames.value || !!candidateNames.value)

// When it's to be recorded, if that's known: its own date, or else its session's
const recording = computed(() => props.idea.recordingDate ?? props.idea.session?.date ?? null)
</script>

<template>
  <li class="idea">
    <div class="main">
      <p class="title">
        <RouterLink :to="path(episodePath(idea))" lang="sl">{{ idea.title }}</RouterLink>
        <ShowBadge v-if="idea.show" :name="idea.show" :color="idea.showColor"/>
      </p>
      <p class="who">
        <span v-if="guestNames" class="people" lang="sl">{{ guestNames }}</span>
        <I18nT v-else-if="candidateNames" keypath="ideas.row.maybe" scope="global" tag="span" class="people">
          <template #names><span lang="sl">{{ candidateNames }}</span></template>
        </I18nT>
        <I18nT v-if="idea.recommendedBy" :keypath="people ? 'ideas.row.recommendedByAfter' : 'ideas.row.recommendedBy'" scope="global"
               tag="span" class="part">
          <template #name><RouterLink class="person" :to="path(personPath(idea.recommendedBy))">{{ idea.recommendedBy.name }}</RouterLink></template>
        </I18nT>
        <I18nT :keypath="people || idea.recommendedBy ? 'ideas.row.addedAfter' : 'ideas.row.added'" scope="global" tag="span" class="part">
          <template #date><time :datetime="idea.addedDate">{{ formatDate(idea.addedDate, dateLocale) }}</time></template>
        </I18nT>
      </p>
      <MarkdownText v-if="idea.tinySummary" class="summary" :source="idea.tinySummary" lang="sl"/>
    </div>
    <span class="date">
      <template v-if="recording">
        <span class="label">{{ t('ideas.row.recording') }} </span>
        <RouterLink v-if="idea.session" :to="path(sessionPath(idea.session))"><time :datetime="recording">{{ formatDate(recording, dateLocale) }}</time></RouterLink>
        <time v-else :datetime="recording">{{ formatDate(recording, dateLocale) }}</time>
      </template>
    </span>
    <span class="files"><FileLinks :of="idea"/></span>
  </li>
</template>

<style scoped lang="scss">

// Columns that line up from row to row, and with the Episodes page's dates and links (EpisodeRow): what it is, when it's
// recorded, its files
.idea {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px 120px;
  column-gap: 16px;
  align-items: start;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
}

.main {
  min-width: 0;
}

.title {
  font-size: var(--text-body);
  line-height: 22px;
  font-weight: 500;
  text-wrap: pretty;

  .show {
    margin-left: 10px;
    vertical-align: 1px;
  }
}

// Who it's with, who recommended it and when it was added, as the Episodes page's rows say who's on an episode; names
// stand out from their labels
.who {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);

  .people, .person {
    color: var(--secondary);
  }

  .person:hover {
    color: var(--accent-text);
  }

  > * + *::before {
    content: '·';
    content: '·' / '';
    margin: 0 6px;
  }
}


.summary {
  max-width: 640px;
  margin-top: 2px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  text-wrap: pretty;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.date {
  font-size: var(--text-small);
  line-height: 22px;
  color: var(--muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;

  a:hover {
    color: var(--accent-text);
  }
}

// Named on phones only, where the date isn't in its column
.label {
  display: none;
}

// The last glyph, not its hit area, lines up with the edge
.files {
  display: flex;
  justify-content: flex-end;
  margin: calc((22px - var(--icon-link)) / 2) var(--icon-link-inset) 0 0;
}

// On phones, each a block: the idea, then when it's recorded, its files beside its title
@media (max-width: 640px) {
  .idea {
    grid-template-columns: minmax(0, 1fr) auto;
    padding: 10px 0;
  }

  .main, .date {
    grid-column: 1;
  }

  .date {
    font-size: var(--text-caption);
    line-height: 18px;
  }

  .date:empty {
    display: none;
  }

  .label {
    display: inline;
  }

  .files {
    grid-column: 2;
    grid-row: 1;
  }
}
</style>
