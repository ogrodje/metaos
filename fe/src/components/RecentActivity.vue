<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { recentActivity } from '@/activity'
import { type Change, type ChangeSubject, DATE_LOCALE, episodePath, sessionPath, TIME_ZONE } from '@/episodes'
import { personPath } from '@/people'

// What the team did lately, on the home page: the latest changes to everything, one person's close together as one
// entry, the newest first, each with what it was to
const props = defineProps<{ changes: Change[] }>()

const entries = computed(() => recentActivity(props.changes))

// The latest few, about as long as the column beside them, until the rest are asked for
const FIRST = 6
const all = ref(false)
const shown = computed(() => (all.value ? entries.value : entries.value.slice(0, FIRST)))

function pathOf(subject: ChangeSubject) {
  if (subject.kind === 'EPISODE') return episodePath(subject)
  if (subject.kind === 'SESSION') return sessionPath(subject)
  return personPath(subject)
}

// Today's by the time, the rest by the day
function when(at: string) {
  const date = new Date(at)
  const day = (d: Date) => d.toLocaleDateString(DATE_LOCALE, { timeZone: TIME_ZONE })
  return day(date) === day(new Date())
    ? date.toLocaleTimeString(DATE_LOCALE, { hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })
    : date.toLocaleDateString(DATE_LOCALE, { day: 'numeric', month: 'short', timeZone: TIME_ZONE })
}
</script>

<template>
  <p v-if="!entries.length" class="note">Nothing yet.</p>
  <ol v-else id="recent-entries" class="recent">
    <li v-for="entry in shown" :key="entry.key">
      <!-- What, then what it was to, each leading to its page -->
      <p class="what">{{ entry.headline }}</p>
      <p v-if="entry.about.length" class="about">
        <template v-for="(subject, i) in entry.about" :key="subject.id">
          <span v-if="i" class="comma">,</span>
          <RouterLink :to="pathOf(subject)" lang="sl"><span v-if="subject.code" class="code">{{ subject.code }}</span>{{ subject.name }}</RouterLink>
        </template>
        <span v-if="entry.more" class="more">and {{ entry.more }} more</span>
      </p>
      <p class="who">
        {{ entry.actor ?? entry.how ?? 'Outside MetaOS' }} · <time :datetime="entry.at">{{ when(entry.at) }}</time>
        <template v-if="entry.count > 1"> · {{ entry.count }} changes</template>
      </p>
    </li>
  </ol>
  <button v-if="entries.length > FIRST" type="button" class="text-button fold" aria-controls="recent-entries"
          :aria-expanded="all" @click="all = !all">
    {{ all ? 'Fold' : `Show all ${entries.length}` }}
  </button>
</template>

<style scoped lang="scss">

.recent {
  padding: 0;
  list-style: none;

  li {
    padding: 10px 0;
    border-top: 1px solid var(--rule);
  }
}

.fold {
  margin-top: 4px;
}

.what {
  font-size: var(--text-small);
  line-height: 20px;
}

.about {
  font-size: var(--text-small);
  line-height: 20px;
  overflow-wrap: anywhere;

  a {
    font-weight: 500;

    &:hover {
      color: var(--accent-text);
    }
  }

  .comma {
    margin-right: 4px;
  }

  .more {
    margin-left: 4px;
    color: var(--muted);
  }
}

.code {
  margin-right: 4px;
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  color: var(--muted);
}

.who {
  margin-top: 2px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
}
</style>
