<script setup lang="ts">
// A group of facts in a FactPanel, e.g. an episode's people: a small heading, then its Facts or whatever it holds
defineProps<{
  title?: string
  count?: number
}>()
</script>

<template>
  <section class="fact-group" :aria-label="title">
    <h2 v-if="title">{{ title }}<span v-if="count !== undefined" class="count">{{ count }}</span></h2>
    <slot/>
  </section>
</template>

<style scoped lang="scss">

.fact-group {
  display: grid;
  gap: 10px;
  padding: 16px 0;
  border-top: 1px solid var(--rule);

  &:first-child {
    padding-top: 0;
    border-top: 0;
  }
}

h2 {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);
}

.count {
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

// Facts, a label and its value a row
:slotted(dl) {
  display: grid;
  gap: 8px;
}

// On phones, the facts of a group side by side, two a row, each value under its label
@media (max-width: 640px) {
  :slotted(dl) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px 16px;
  }
}
</style>
