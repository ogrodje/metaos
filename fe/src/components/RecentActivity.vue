<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { recentActivity } from '@/activity'
import { type Change, type ChangeSubject, episodePath, sessionPath } from '@/episodes'
import { TIME_ZONE } from '@/i18n'
import { personPath } from '@/people'
import { useLocale } from '@/useLocale'

// What the team did lately, on the home page: the latest changes to everything, one person's close together as one
// entry, the newest first, each with what it was to
const props = defineProps<{ changes: Change[] }>()

const { t, dateLocale, path } = useLocale()

const entries = computed(() => recentActivity(props.changes, t, dateLocale.value))

// The latest few, about as long as the column beside them, until the rest are asked for
const FIRST = 6
const all = ref(false)
const shown = computed(() => (all.value ? entries.value : entries.value.slice(0, FIRST)))

function pathOf(subject: ChangeSubject) {
  if (subject.kind === 'EPISODE') return path(episodePath(subject))
  if (subject.kind === 'SESSION') return path(sessionPath(subject))
  return path(personPath(subject))
}

// Today's by the time, the rest by the day
function when(at: string) {
  const date = new Date(at)
  const day = (d: Date) => d.toLocaleDateString('en-GB', { timeZone: TIME_ZONE })
  return day(date) === day(new Date())
    ? date.toLocaleTimeString(dateLocale.value, { hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })
    : date.toLocaleDateString(dateLocale.value, { day: 'numeric', month: 'short', timeZone: TIME_ZONE })
}
</script>

<template>
  <p v-if="!entries.length" class="note">{{ t('activity.nothing') }}</p>
  <ol v-else id="recent-entries" class="recent">
    <li v-for="entry in shown" :key="entry.key">
      <!-- What, then what it was to, each leading to its page -->
      <p class="what">{{ entry.headline }}</p>
      <p v-if="entry.about.length" class="about">
        <template v-for="(subject, i) in entry.about" :key="subject.id">
          <span v-if="i" class="comma">,</span>
          <RouterLink :to="pathOf(subject)" lang="sl"><span v-if="subject.code" class="code">{{ subject.code }}</span>{{ subject.name }}</RouterLink>
        </template>
        <span v-if="entry.more" class="more">{{ t('activity.andMore', { n: entry.more }, entry.more) }}</span>
      </p>
      <p class="who">
        {{ entry.actor ?? entry.how ?? t('activity.outside') }} · <time :datetime="entry.at">{{ when(entry.at) }}</time>
        <template v-if="entry.count > 1"> · {{ t('activity.changes', { n: entry.count }, entry.count) }}</template>
      </p>
    </li>
  </ol>
  <button v-if="entries.length > FIRST" type="button" class="text-button fold" aria-controls="recent-entries"
          :aria-expanded="all" @click="all = !all">
    {{ all ? t('activity.fold') : t('activity.showAll', { n: entries.length }) }}
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
