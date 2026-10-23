<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { type EpisodeStatus, statusName } from '@/episodes'

// How many episodes are at each stage, from idea to published: those being made marked, the empty ones quiet. Each
// leads to where they're listed: the ideas to Ideas, the rest to Episodes.
defineProps<{ counts: { status: EpisodeStatus, count: number }[] }>()

const MAKING: EpisodeStatus[] = ['RECORDED', 'IN_PRODUCTION', 'READY', 'EARLY_ACCESS']
</script>

<template>
  <ol class="stages" aria-label="Episodes by stage">
    <li v-for="{ status, count } in counts" :key="status">
      <RouterLink :to="status === 'IDEA' ? '/ideas' : '/episodes'" class="stage"
                  :class="{ empty: !count, making: count && MAKING.includes(status) }">
        <b>{{ count }}</b><span>{{ status === 'IDEA' ? 'Ideas' : statusName(status) }}</span>
      </RouterLink>
    </li>
  </ol>
</template>

<style scoped lang="scss">

.stages {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  overflow: hidden;
  padding: 0;
  list-style: none;
  border: 1px solid var(--hairline);
  border-radius: 10px;
}

li + li {
  border-left: 1px solid var(--rule);
}

.stage {
  display: grid;
  gap: 2px;
  padding: 10px 12px;

  &:hover {
    background: var(--hover);
  }

  &:focus-visible {
    outline-offset: -2px;
  }

  b {
    font-size: 20px;
    line-height: 28px;
    font-weight: 600;
    letter-spacing: -0.01em;
    font-variant-numeric: tabular-nums;
  }

  span {
    overflow: hidden;
    font-size: 12px;
    line-height: 16px;
    color: var(--muted);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &.empty b {
    font-weight: 500;
    color: #C9C9C9;
  }

  &.making {
    background: #FFF7F9;

    b {
      color: var(--accent-text);
    }
  }
}

// On phones two rows of four
@media (max-width: 640px) {
  .stages {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  li:nth-child(5) {
    border-left: 0;
  }

  li:nth-child(n+5) {
    border-top: 1px solid var(--rule);
  }

  .stage {
    padding: 8px 10px;
  }
}
</style>
