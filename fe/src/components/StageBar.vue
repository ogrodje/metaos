<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { type EpisodeStatus, statusName } from '@/episodes'

// How many episodes are at each stage, from idea to published, a line each at the side of the home page: those being
// made marked, the empty ones quiet. Each leads to where they're listed: the ideas to Ideas, the rest to Episodes, with
// only that stage (?status=).
defineProps<{ counts: { status: EpisodeStatus, count: number }[] }>()

const MAKING: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS']

function stagePath(status: EpisodeStatus) {
  return status === 'IDEA' ? '/ideas' : { path: '/episodes', query: { status } }
}
</script>

<template>
  <ol class="stages" aria-label="Episodes by stage">
    <li v-for="{ status, count } in counts" :key="status">
      <RouterLink v-if="count" :to="stagePath(status)" class="stage" :class="{ making: MAKING.includes(status) }">
        <span class="name">{{ status === 'IDEA' ? 'Ideas' : statusName(status) }}</span><b>{{ count }}</b>
      </RouterLink>
      <span v-else class="stage empty">
        <span class="name">{{ status === 'IDEA' ? 'Ideas' : statusName(status) }}</span><b>0</b>
      </span>
    </li>
  </ol>
</template>

<style scoped lang="scss">

.stages {
  padding: 0;
  list-style: none;
}

li {
  border-top: 1px solid var(--rule);
}

.stage {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  font-size: var(--text-small);
  line-height: 20px;

  b {
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}

a.stage {
  &:hover .name {
    color: var(--accent-text);
  }

  &:focus-visible {
    outline-offset: 0;
  }
}

// Those being made: their counts in the accent
.making b {
  color: var(--accent-text);
}

// None at this stage: nothing to go to
.empty {
  color: var(--muted);

  b {
    font-weight: 400;
  }
}
</style>
