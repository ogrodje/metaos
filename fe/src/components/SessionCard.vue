<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import EpisodeRow from '@/components/EpisodeRow.vue'
import FileLinks from '@/components/FileLinks.vue'
import { formatDate, relativeDate, sessionName, sessionPath, type SessionGroup, TIME_ZONE } from '@/episodes'

// A recording session on the home page and its episodes: the day as on a calendar, where, how far away, its files, and
// its episodes a line each. Episodes without a session are rows of their own, without the head.
const props = defineProps<{
  group: SessionGroup
  // A date that passed while it's still to be recorded: what needs attention says why
  overdue?: boolean
}>()

const day = computed(() => {
  const date = props.group.session ? new Date(props.group.session.date) : null
  return date && {
    month: date.toLocaleDateString('en-GB', { month: 'short', timeZone: TIME_ZONE }),
    day: date.toLocaleDateString('en-GB', { day: 'numeric', timeZone: TIME_ZONE }),
  }
})
</script>

<template>
  <div class="session-card" :class="{ loose: !group.session }">
    <h3 v-if="group.session" class="head">
      <span class="calendar" aria-hidden="true"><span class="month">{{ day?.month }}</span><span class="day">{{ day?.day }}</span></span>
      <span class="where">
        <RouterLink :to="sessionPath(group.session)">{{ sessionName(group.session) ?? formatDate(group.session.date) }}</RouterLink>
        <time class="date" :datetime="group.session.date">{{ formatDate(group.session.date) }}</time>
      </span>
      <span class="when" :class="{ overdue }">{{ relativeDate(group.session.date) }}</span>
      <span class="files"><FileLinks :of="group.session"/></span>
    </h3>
    <ul class="episodes">
      <EpisodeRow v-for="episode in group.episodes" :key="episode.id" :episode="episode" under-session/>
    </ul>
  </div>
</template>

<style scoped lang="scss">

// A part of its section under a hairline, as a row is, not a box: the head, then its episodes under its name
.session-card {
  padding-top: 14px;
  border-top: 1px solid var(--hairline);

  & + & {
    margin-top: 10px;
  }

  // Episodes without a session: rows as any list's
  &.loose {
    padding-top: 0;
    border-top: 0;
  }
}

.head {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto auto;
  align-items: center;
  column-gap: 16px;
  font-weight: 400;
}

// The day, as on a calendar: the month over it, in the page's greys
.calendar {
  display: grid;
  padding: 4px 0;
  text-align: center;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);

  .month {
    font-size: var(--text-micro);
    line-height: 14px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }

  .day {
    font-size: var(--text-section);
    line-height: 22px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}

.where {
  display: grid;
  min-width: 0;

  a {
    font-size: var(--text-lead);
    line-height: 22px;
    font-weight: 600;
    letter-spacing: -0.01em;

    &:hover {
      color: var(--accent-text);
    }
  }
}

.date {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
}

.when {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
  white-space: nowrap;

  &.overdue {
    color: var(--accent-text);
    font-weight: 500;
  }
}

.files {
  display: flex;
}

// Under the session's name, past its calendar
.episodes {
  margin-top: 6px;
  padding-left: 60px;
  list-style: none;

  :deep(.episode:first-child) {
    border-top: 0;
  }
}

.loose .episodes {
  margin-top: 0;
  padding-left: 0;

  :deep(.episode:first-child) {
    border-top: 1px solid var(--hairline);
  }
}

@media (max-width: 640px) {
  .head {
    grid-template-columns: 44px minmax(0, 1fr) auto;
    column-gap: 12px;
  }

  .files {
    display: none;
  }

  .episodes {
    padding-left: 0;
  }
}
</style>
