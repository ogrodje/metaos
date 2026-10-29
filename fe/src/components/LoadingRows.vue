<script setup lang="ts">
// A list's place while it loads: rows as tall and ruled as the list's, so the page doesn't jump when it comes. They
// show only when loading takes a moment, so a quick load doesn't flash them.
withDefaults(defineProps<{
  rows?: number
  // Episodes and ideas with their summary (EpisodeItem, PersonItem) are taller than a line (EpisodeRow)
  tall?: boolean
  // Said to screen readers, e.g. "Loading the episodes"
  label?: string
}>(), { rows: 4, tall: false, label: 'Loading' })
</script>

<template>
  <div class="loading" :class="{ tall }" role="status">
    <span class="visually-hidden">{{ label }}…</span>
    <div v-for="row in rows" :key="row" class="row" aria-hidden="true">
      <span class="bar code"/>
      <span class="lines">
        <span class="bar title" :style="{ width: `${68 - (row % 3) * 14}%` }"/>
        <span v-if="tall" class="bar text"/>
      </span>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '../assets/episode';

.loading {
  animation: appear 0.2s ease-out 0.3s both;
}

.row {
  display: grid;
  grid-template-columns: episode.$code-width minmax(0, 1fr);
  column-gap: 16px;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
}

.tall .row {
  padding: 20px 0;
}

.lines {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bar {
  display: block;
  height: 10px;
  margin: 6px 0;
  border-radius: var(--radius-xs);
  background: var(--hover);
}

.code {
  width: 48px;
}

.tall .title {
  height: 14px;
}

.text {
  width: 88%;
}

@media (max-width: 640px) {
  .row {
    grid-template-columns: episode.$code-width-phone minmax(0, 1fr);
  }
}

@keyframes appear {
  from {
    opacity: 0;
  }
}

</style>
