<script setup lang="ts">
import { faArrowUpRightFromSquare, faLocationDot } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { computed } from 'vue'
import { badgeColors, eventTime, type GooEvent } from '@/events'
import { useLocale } from '@/useLocale'

const props = defineProps<{ event: GooEvent }>()
const { t, dateLocale } = useLocale()

const when = computed(() => eventTime(props.event, dateLocale.value))
const badge = computed(() => (props.event.meetup ? badgeColors(props.event.meetup) : null))
// Where it is, when Goo knows both the place and its address, as ogrodje.si showed it
const where = computed(() => {
  const { locationName, locationAddress } = props.event
  if (!locationName || !locationAddress) return null
  return locationName === locationAddress ? locationName : `${locationName}, ${locationAddress}`
})
</script>

<!-- An event on a line or two: its title, which opens where it's announced in a new tab, who it's by, as a badge in their
     colours with their logo, and where it is; when it is at the right, or on phones under the title. Promoted ones, Ogrodje's
     picks, stand out in the accent. -->
<template>
  <li class="event" :class="{ promoted: event.promoted }">
    <div class="main">
      <p class="title">
        <a v-if="event.url" :href="event.url" target="_blank" rel="noopener">
          {{ event.title }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/><span class="visually-hidden"> {{ t('events.newTab') }}</span>
        </a>
        <span v-else>{{ event.title }}</span>
        <span v-if="event.promoted" class="tag">{{ t('events.promoted') }}</span>
      </p>
      <p v-if="event.meetup || where" class="details">
        <span v-if="event.meetup && badge" class="meetup" :style="{ color: badge.color, background: badge.background }">
          <img v-if="event.meetup.logo" :src="event.meetup.logo" alt="" width="20" height="20" loading="lazy">{{ event.meetup.name }}
        </span>
        <span v-if="where" class="where"><FontAwesomeIcon class="icon" :icon="faLocationDot"/>{{ where }}</span>
      </p>
    </div>
    <time class="when" :datetime="event.start">{{ when }}</time>
  </li>
</template>

<style scoped lang="scss">
.event {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  column-gap: 16px;
  align-items: start;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
}

// Ogrodje's picks: a mark in the accent at the row's start, and a wash behind it
.promoted {
  margin: 0 -12px;
  padding: 11px 12px;
  background: var(--accent-wash);
  box-shadow: inset 3px 0 0 var(--accent);
  border-radius: var(--radius-xs);
}

.title {
  font-size: var(--text-body);
  line-height: 22px;
  font-weight: 500;
  text-wrap: pretty;

  .external {
    width: 10px;
    height: 10px;
    margin-left: 6px;
    color: var(--muted);
    vertical-align: 0;
  }
}

.tag {
  margin-left: 10px;
  padding: 0 6px;
  border-radius: var(--radius-xs);
  background: var(--mark);
  font-size: var(--text-micro);
  line-height: 18px;
  font-weight: 600;
  color: var(--accent-text);
  vertical-align: 1px;
  white-space: nowrap;
}

.details {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-top: 6px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

// Who it's by, in their colours, with their logo
.meetup {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-height: 24px;
  padding: 2px 8px 2px 2px;
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  // An edge, for a badge as white as the page
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .08);

  // Without a logo, the name has the badge to itself
  &:not(:has(img)) {
    padding-left: 8px;
  }

  img {
    flex: none;
    width: 20px;
    height: 20px;
    object-fit: cover;
    border-radius: 3px;
    background: #FFFFFF;
  }
}

.where {
  .icon {
    width: 11px;
    height: 11px;
    margin-right: 6px;
    color: var(--muted);
    vertical-align: -1px;
  }
}

.when {
  font-size: var(--text-small);
  line-height: 22px;
  color: var(--secondary);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

// On phones when it is goes under the title, above who it's by
@media (max-width: 640px) {
  .event {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas: 'title' 'when' 'details';
    padding: 10px 0;
  }

  .promoted {
    padding: 10px 12px;
  }

  .main {
    display: contents;
  }

  .title {
    grid-area: title;
  }

  .when {
    grid-area: when;
    text-align: left;
    font-size: var(--text-caption);
    line-height: 18px;
  }

  .details {
    grid-area: details;
    margin-top: 4px;
  }
}
</style>
