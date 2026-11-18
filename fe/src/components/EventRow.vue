<script setup lang="ts">
import { faArrowUpRightFromSquare, faLocationDot } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { computed, nextTick, ref, useId } from 'vue'
import EventForm from '@/components/EventForm.vue'
import MeetupBadge from '@/components/MeetupBadge.vue'
import { eventTime, type EventInput, isManual, type ListedEvent, sourceName, type TeamEvent, UPDATE_EVENT } from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { useLocale } from '@/useLocale'

// manage: the team's, with the event's team fields: Hide or Show, Pick (promoted) or Unpick, and Edit for their own;
// a hidden one dimmed, with a tag. The page passes it only once it knows it's the team's, so the server renders it as
// anyone sees it.
const props = defineProps<{
  event: ListedEvent | TeamEvent
  manage?: boolean
}>()

const emit = defineEmits<{ changed: [event: TeamEvent] }>()
const { t, dateLocale } = useLocale()
const id = useId()

const team = computed(() => (props.manage && 'hidden' in props.event ? props.event : null))

const busy = ref(false)
const error = ref<string>()
const editing = ref(false)

async function update(input: EventInput) {
  if (busy.value || !team.value) return
  busy.value = true
  error.value = undefined
  try {
    const { event } = await graphql<{ event: TeamEvent }>(UPDATE_EVENT, { id: team.value.id, input })
    emit('changed', event)
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

function stopEditing() {
  editing.value = false
  void nextTick(() => document.getElementById(`${id}-edit`)?.focus())
}

const when = computed(() => eventTime(props.event, dateLocale.value))
// Where it is, when MetaOS knows both the place and its address, as ogrodje.si showed it
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
  <li class="event" :class="{ promoted: event.promoted, hidden: team?.hidden }">
    <div class="main">
      <p class="title">
        <a v-if="event.url" :href="event.url" target="_blank" rel="noopener">
          {{ event.title }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/><span class="visually-hidden"> {{ t('events.newTab') }}</span>
        </a>
        <span v-else>{{ event.title }}</span>
        <span v-if="event.promoted" class="tag">{{ t('events.promoted') }}</span>
        <span v-if="team?.hidden" class="tag quiet">{{ t('events.team.hidden') }}</span>
      </p>
      <p v-if="event.meetup || where" class="details">
        <MeetupBadge v-if="event.meetup" :meetup="event.meetup"/>
        <span v-if="where" class="where"><FontAwesomeIcon class="icon" :icon="faLocationDot"/>{{ where }}</span>
      </p>
    </div>
    <time class="when" :datetime="event.start">{{ when }}</time>

    <div v-if="team && !editing" class="manage">
      <span class="source">
        <template v-if="isManual(team)">{{ t('events.team.manual') }}</template>
        <a v-else-if="team.sourceUrl" :href="team.sourceUrl" target="_blank" rel="noopener">{{ t('events.team.foundOn', { source: sourceName(team.source) }) }}</a>
        <template v-else>{{ t('events.team.foundOn', { source: sourceName(team.source) }) }}</template>
      </span>
      <span class="actions">
        <button type="button" class="button small quiet" :aria-disabled="busy || undefined"
                :aria-label="team.hidden ? t('events.team.showLabel', { title: event.title }) : t('events.team.hideLabel', { title: event.title })"
                @click="update({ hidden: !team.hidden })">{{ team.hidden ? t('events.team.show') : t('events.team.hide') }}</button>
        <button type="button" class="button small quiet" :aria-disabled="busy || undefined" :aria-pressed="team.promoted"
                :aria-label="t('events.team.pickLabel', { title: event.title })"
                @click="update({ promoted: !team.promoted })">{{ team.promoted ? t('events.team.unpick') : t('events.team.pick') }}</button>
        <button v-if="isManual(team)" :id="`${id}-edit`" type="button" class="button small quiet"
                :aria-label="t('events.team.editLabel', { title: event.title })" @click="editing = true">{{ t('common.edit') }}</button>
      </span>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </div>
    <EventForm v-if="team && editing" class="form" :event="team" @saved="(saved) => emit('changed', saved)" @close="stopEditing"/>
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

// The team's: what's hidden from everyone else, dimmed
.hidden {
  .title, .details, .when {
    opacity: .55;
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

  &.quiet {
    background: var(--hover);
    color: var(--secondary);
  }
}

// The team's, under it: who found it, and what they do with it
.manage {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-top: 6px;
}

.source {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);

  a {
    color: inherit;
    text-decoration: underline;
    text-decoration-color: var(--faint);
    text-underline-offset: 3px;

    &:hover {
      color: var(--ink);
    }
  }
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: auto;
}

.error {
  flex-basis: 100%;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

.form {
  grid-column: 1 / -1;
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

  .actions {
    margin-left: 0;
  }
}
</style>
