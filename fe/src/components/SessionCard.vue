<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import EpisodeRow from '@/components/EpisodeRow.vue'
import FileLinks from '@/components/FileLinks.vue'
import { formatDate, relativeDate, sessionName, sessionPath, type SessionGroup, TIME_ZONE } from '@/episodes'

// A recording session on the home page and its episodes: the day as on a calendar, where, how far away, its files, and
// its episodes a line each. Episodes without a session are a card of their own, without the head.
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
  <div class="session-card">
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

.session-card {
  overflow: hidden;
  border: 1px solid var(--hairline);
  border-radius: 12px;

  & + & {
    margin-top: 14px;
  }
}

.head {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto auto;
  align-items: center;
  column-gap: 14px;
  padding: 10px 14px;
  font-weight: 400;
  background: #FAFAFA;
  border-bottom: 1px solid var(--rule);
}

// The day, as on a calendar
.calendar {
  display: grid;
  overflow: hidden;
  text-align: center;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 8px;

  .month {
    font-size: 10.5px;
    line-height: 16px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: #FFFFFF;
    background: var(--accent-text);
  }

  .day {
    font-size: 18px;
    line-height: 26px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}

.where {
  display: grid;
  min-width: 0;

  a {
    font-size: 14px;
    line-height: 20px;
    font-weight: 600;
  }
}

.date {
  font-size: 12.5px;
  line-height: 18px;
  color: var(--muted);
}

.when {
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
  white-space: nowrap;

  &.overdue {
    color: var(--accent-text);
  }
}

.files {
  display: flex;
}

.episodes {
  padding: 0 14px;
  list-style: none;

  :deep(.episode:first-child) {
    border-top: 0;
  }
}

@media (max-width: 640px) {
  .head {
    grid-template-columns: 44px minmax(0, 1fr) auto;
    column-gap: 10px;
    padding: 10px 12px;
  }

  .files {
    display: none;
  }

  .episodes {
    padding: 0 12px;
  }
}
</style>
