<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import FileLinks from '@/components/FileLinks.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import PersonLine from '@/components/PersonLine.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import { candidates, episodePath, formatDate, guests, type Idea, sessionPath } from '@/episodes'

// An idea in the Ideas table: its title, show, and who it's with, its guests, or who might be (its candidates), and its
// tiny summary; who recommended it; when it's to be recorded; when it was added; its files. On phones, a block of lines.
const props = defineProps<{ idea: Idea }>()

const guestNames = computed(() => guests(props.idea).join(', '))
const candidateNames = computed(() => candidates(props.idea).join(', '))

// When it's to be recorded, if that's known: its own date, or else its session's
const recording = computed(() => props.idea.recordingDate ?? props.idea.session?.date ?? null)
</script>

<template>
  <tr class="idea">
    <td class="what">
      <p class="title">
        <RouterLink :to="episodePath(idea)" lang="sl">{{ idea.title }}</RouterLink>
        <ShowBadge v-if="idea.show" :name="idea.show" :color="idea.showColor"/>
      </p>
      <p v-if="guestNames" class="who">{{ guestNames }}</p>
      <p v-else-if="candidateNames" class="who">Maybe {{ candidateNames }}</p>
      <MarkdownText v-if="idea.tinySummary" class="summary" :source="idea.tinySummary" lang="sl"/>
    </td>
    <td class="by">
      <PersonLine v-if="idea.recommendedBy" :id="idea.recommendedBy.id" :name="idea.recommendedBy.name" :avatar="idea.recommendedBy.avatar"/>
    </td>
    <td class="date">
      <template v-if="recording">
        <span class="label">Recording </span>
        <RouterLink v-if="idea.session" :to="sessionPath(idea.session)"><time :datetime="recording">{{ formatDate(recording) }}</time></RouterLink>
        <time v-else :datetime="recording">{{ formatDate(recording) }}</time>
      </template>
    </td>
    <td class="date"><span class="label">Added </span><time :datetime="idea.addedDate">{{ formatDate(idea.addedDate) }}</time></td>
    <td class="files"><span><FileLinks :of="idea"/></span></td>
  </tr>
</template>

<style scoped lang="scss">

td {
  padding: 10px 16px 10px 0;
  vertical-align: top;
  border-top: 1px solid var(--rule);
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
}

.who {
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
}

.summary {
  margin-top: 2px;
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
  text-wrap: pretty;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.by {
  padding-top: 9px;
}

.date {
  font-size: 13px;
  line-height: 22px;
  color: var(--muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;

  a:hover {
    color: var(--accent-text);
  }
}

// Named on phones, where they're not under the table's header
.label {
  display: none;
}

.files {
  padding-right: 0;

  span {
    display: flex;
    justify-content: flex-end;
    margin: calc((22px - var(--icon-link)) / 2) var(--icon-link-inset) 0 0;
  }
}

// On phones, each a block: the idea, then who recommended it and its dates, then its files
@media (max-width: 640px) {
  .idea {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    column-gap: 8px;
    padding: 10px 0;
    border-top: 1px solid var(--rule);
  }

  td {
    padding: 0;
    border: 0;
  }

  .what, .by, .date {
    grid-column: 1;
  }

  .by {
    padding: 4px 0 0;
  }

  .date {
    font-size: 12px;
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
