<script setup lang="ts">
// One fact in a FactGroup's <dl>: its label, and its value, e.g. Aired, 13 Oct 2026; stacked, the value under its label,
// for one too long to fit beside it, e.g. a folder's path
defineProps<{
  label: string
  stacked?: boolean
}>()
</script>

<template>
  <div class="fact" :class="{ stacked }">
    <dt>{{ label }}</dt>
    <dd><slot/></dd>
  </div>
</template>

<style scoped lang="scss">

.fact {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  column-gap: 8px;
  align-items: baseline;
}

.stacked {
  grid-template-columns: minmax(0, 1fr);
  row-gap: 2px;
}

dt {
  color: var(--muted);
}

dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 640px) {
  .fact {
    grid-template-columns: minmax(0, 1fr);
  }

  // Across both of the group's columns
  .stacked {
    grid-column: 1 / -1;
  }
}
</style>
