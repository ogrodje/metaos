<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import MeetupBadge from '@/components/MeetupBadge.vue'
import { eventPath, type ListedEvent, pickWhen, placeLine } from '@/events'
import { useLocale } from '@/useLocale'

// One of Ogrodje's picks at the top of the Events page: a card, as it stands alone there, the whole of it a link to the
// event's page: when it is, in the accent, as it's what to look at, its title, who it's by and where
const props = defineProps<{ event: ListedEvent, today: string }>()

const { t, dateLocale, path } = useLocale()

const when = computed(() => pickWhen(props.event, props.today, t, dateLocale.value))
const where = computed(() => placeLine(props.event.place ?? { name: props.event.locationName, address: props.event.locationAddress }))
</script>

<template>
  <li class="pick">
    <RouterLink class="card" :to="path(eventPath(event))">
      <time class="when" :datetime="event.start">{{ when }}</time>
      <span class="title">{{ event.title }}</span>
      <span v-if="event.meetup || where" class="details">
        <MeetupBadge v-if="event.meetup" :meetup="event.meetup"/>
        <span v-if="where" class="where">{{ where }}</span>
      </span>
    </RouterLink>
  </li>
</template>

<style scoped lang="scss">
.pick {
  display: flex;
  min-width: 0;
}

// What stands alone is a card: outlined, not lifted, its edge stronger while it's pointed at
.card {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-lg);

  &:hover {
    border-color: var(--hairline-strong);
  }
}

.when {
  font-size: var(--text-small);
  line-height: 20px;
  font-weight: 500;
  color: var(--accent-text);
}

.title {
  font-size: var(--text-lead);
  line-height: 24px;
  font-weight: 600;
  letter-spacing: -0.01em;
  text-wrap: pretty;
}

// Who it's by and where, at the card's end, on a line: the place gives way
.details {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-top: auto;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);

  :deep(.meetup) {
    flex: none;
    max-width: 60%;
    overflow: hidden;
    white-space: nowrap;
  }
}

.where {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
