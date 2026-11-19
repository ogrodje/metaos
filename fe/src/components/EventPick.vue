<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import MeetupBadge from '@/components/MeetupBadge.vue'
import { relativeDate } from '@/episodes'
import { dayOf, eventPath, type ListedEvent, placeLine } from '@/events'
import { TIME_ZONE } from '@/i18n'
import { useLocale } from '@/useLocale'

// One of Ogrodje's picks at the top of the Events page, as a recording session is on the home page (SessionCard): its
// day as on a calendar, its title, which opens its page, how far away it is, who it's by and where
const props = defineProps<{ event: ListedEvent }>()

const { dateLocale, path } = useLocale()

const day = computed(() => {
  const date = new Date(props.event.start)
  return {
    month: date.toLocaleDateString(dateLocale.value, { month: 'short', timeZone: TIME_ZONE }),
    day: date.toLocaleDateString(dateLocale.value, { day: 'numeric', timeZone: TIME_ZONE }),
  }
})
const when = computed(() => relativeDate(dayOf(props.event.start), dateLocale.value))
const where = computed(() => placeLine(props.event.place ?? { name: props.event.locationName, address: props.event.locationAddress }))
</script>

<template>
  <li class="pick">
    <span class="calendar" aria-hidden="true"><span class="month">{{ day.month }}</span><span class="day">{{ day.day }}</span></span>
    <div class="what">
      <RouterLink class="title" :to="path(eventPath(event))">{{ event.title }}</RouterLink>
      <time class="when" :datetime="event.start">{{ when }}</time>
      <p v-if="event.meetup || where" class="details">
        <MeetupBadge v-if="event.meetup" :meetup="event.meetup"/>
        <span v-if="where" class="where">{{ where }}</span>
      </p>
    </div>
  </li>
</template>

<style scoped lang="scss">
// Under a hairline, as a session on the home page is, not a box
.pick {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  column-gap: 16px;
  align-items: start;
  padding-top: 14px;
  border-top: 1px solid var(--hairline);
}

// The day, as on a calendar: the month over it, as SessionCard's
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

.what {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.title {
  font-size: var(--text-lead);
  line-height: 22px;
  font-weight: 600;
  letter-spacing: -0.01em;
  text-wrap: pretty;

  &:hover {
    color: var(--accent-text);
  }
}

.when {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.details {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  padding-top: 4px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

.where {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
