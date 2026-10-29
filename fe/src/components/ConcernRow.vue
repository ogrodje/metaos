<script setup lang="ts">
import { RouterLink } from 'vue-router'
import ShowBadge from '@/components/ShowBadge.vue'
import type { Concern } from '@/attention'
import { episodePath, formatDate, relativeDate } from '@/episodes'

// An episode that needs attention, on the home page: the code and title, what's wrong, and when it's recorded
defineProps<{ concern: Concern }>()
</script>

<template>
  <li class="episode">
    <span class="code" :aria-hidden="!concern.episode.code">{{ concern.episode.code ?? '—' }}</span>
    <div class="main">
      <p class="title">
        <RouterLink :to="episodePath(concern.episode)" lang="sl">{{ concern.episode.title }}</RouterLink>
        <ShowBadge v-if="concern.episode.show" :name="concern.episode.show" :color="concern.episode.showColor"/>
      </p>
      <p class="reasons">
        <span v-for="reason in concern.reasons" :key="reason">{{ reason }}</span>
      </p>
    </div>
    <time v-if="concern.date" class="when" :datetime="concern.date" :title="formatDate(concern.date)">
      {{ relativeDate(concern.date) }}
    </time>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/episode';

.episode {
  display: grid;
  grid-template-columns: episode.$code-width minmax(0, 1fr) auto;
  align-items: start;
  padding: 11px 0;
}

.title {
  font-size: var(--text-body);
  line-height: 22px;
  font-weight: 500;

  .show {
    margin-left: 10px;
    vertical-align: 1px;
  }
}

// What's wrong, in the accent's text colour: the one thing on the page that asks for something
.reasons {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);

  > * + *::before {
    content: '·';
    content: '·' / '';
    margin: 0 6px;
    color: var(--muted);
  }
}

.when {
  font-size: var(--text-small);
  line-height: 22px;
  color: var(--muted);
  white-space: nowrap;
}

// On phones when goes under what's wrong
@media (max-width: 640px) {
  .episode {
    grid-template-columns: episode.$code-width-phone minmax(0, 1fr);
    padding: 10px 0;
  }

  .when {
    grid-column: 2;
    font-size: var(--text-caption);
    line-height: 18px;
  }
}

</style>
