<script setup lang="ts">
import { computed } from 'vue'
import { type Change, DATE_LOCALE, type FieldChange } from '@/episodes'

// Changes oldest first, as the API has them
const props = defineProps<{ changes: Change[] }>()

// Newest first, each day's date only by its first change
const entries = computed(() => {
  let previousDay: string | undefined
  return [...props.changes].reverse().map((change, index) => {
    const at = new Date(change.at)
    const day = at.toLocaleDateString(DATE_LOCALE, { dateStyle: 'medium' })
    const entry = {
      key: `${change.at}-${index}`,
      change,
      day: day !== previousDay ? day : null,
      time: at.toLocaleTimeString(DATE_LOCALE, { hour: '2-digit', minute: '2-digit' }),
      by: by(change),
    }
    previousDay = day
    return entry
  })
})

// Who made a change, and how. MCP clients register themselves, so they go by the tool they used rather than a name.
function by(change: Change) {
  const how = change.tool ? `MCP · ${change.tool}` : change.client === 'metaos-ui' ? 'MetaOS' : change.client ? 'MCP' : null
  const parts = [change.actor, how].filter(Boolean)
  // E.g. a migration
  return parts.length ? parts.join(' · ') : 'Outside MetaOS'
}

function value(field: FieldChange) {
  if (field.from === null && field.to === null) return 'changed'
  if (field.from === null) return field.to
  if (field.to === null) return `removed (was ${field.from})`
  return `${field.from} → ${field.to}`
}
</script>

<!-- A date rail: when on the left, what happened and who did it on the right -->
<template>
  <ol class="timeline">
    <li v-for="entry in entries" :key="entry.key" class="entry">
      <div class="when">
        <time v-if="entry.day" class="day" :datetime="entry.change.at">{{ entry.day }}</time>
        <time class="time" :datetime="entry.change.at">{{ entry.time }}</time>
      </div>
      <div class="rail" aria-hidden="true"><span class="dot"></span></div>
      <div class="what">
        <p class="summary">{{ entry.change.summary }}</p>
        <ul v-if="entry.change.fields.length" class="fields">
          <li v-for="field in entry.change.fields" :key="field.field">
            <span class="field">{{ field.field }}</span> {{ value(field) }}
          </li>
        </ul>
        <p class="by">{{ entry.by }}</p>
      </div>
    </li>
  </ol>
</template>

<style scoped lang="scss">

.timeline {
  padding: 0;
  list-style: none;
}

.entry {
  display: grid;
  grid-template-columns: 112px 24px minmax(0, 1fr);
  column-gap: 12px;
}

.when {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding: 12px 0;
}

.day {
  font-size: 13px;
  line-height: 22px;
  font-weight: 600;
}

.time {
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

// The rail runs from the newest change's dot to the oldest's
.rail {
  position: relative;

  &::before {
    content: '';
    position: absolute;
    left: 11px;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--hairline);
  }
}

.entry:first-child .rail::before {
  top: 21px;
}

.entry:last-child .rail::before {
  bottom: auto;
  height: 21px;
}

.dot {
  position: absolute;
  left: 7px;
  top: 17px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #FFFFFF;
  border: 1px solid #A3A3A3;
}

// Where it all began
.entry:last-child .dot {
  background: var(--accent);
  border-color: var(--accent);
}

.what {
  min-width: 0;
  padding: 12px 0;
}

.summary {
  font-size: 14px;
  line-height: 22px;
  font-weight: 500;
}

.fields {
  margin-top: 2px;
  padding: 0;
  list-style: none;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
  overflow-wrap: anywhere;

  .field {
    color: var(--muted);
  }
}

.by {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
}

// On phones the date goes above the change, beside the rail
@media (max-width: 640px) {
  .entry {
    grid-template-columns: 24px minmax(0, 1fr);
    column-gap: 8px;
  }

  .rail {
    grid-column: 1;
    grid-row: 1 / span 2;
  }

  .when {
    grid-column: 2;
    flex-direction: row;
    align-items: baseline;
    gap: 8px;
    padding-bottom: 0;
  }

  .what {
    grid-column: 2;
    padding-top: 0;
  }
}

</style>
