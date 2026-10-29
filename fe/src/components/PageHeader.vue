<script setup lang="ts">
// A list page's heading: its name, how many it has, and a line about what it lists; while it's filtered, how many of them
// are shown, e.g. 12 of 74, which screen readers hear as it changes. What the page does besides listing, e.g. transcribe
// an episode, is a button at the end of its name's line (actions).
defineProps<{
  title: string
  count?: number
  shown?: number
}>()
</script>

<template>
  <header class="page-header">
    <div class="name">
      <h1>{{ title }}</h1>
      <span v-if="count !== undefined" class="count" aria-live="polite" aria-atomic="true">
        {{ shown === undefined ? count : `${shown} of ${count}` }}<span class="visually-hidden"> {{ title.toLowerCase() }}</span>
      </span>
      <div v-if="$slots.actions" class="actions"><slot name="actions"/></div>
    </div>
    <p v-if="$slots.default" class="lede"><slot/></p>
  </header>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.page-header {
  display: grid;
  gap: 6px;
}

.name {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
}

// At the line's end, or under the name where they don't fit beside it, e.g. on phones
.actions {
  display: flex;
  gap: 8px;
  align-self: center;
  margin-left: auto;
  white-space: nowrap;
}

@media (max-width: 640px) {
  .actions {
    order: 1;
    width: 100%;
    margin: 4px 0 0;
  }
}

h1 {
  font-size: var(--text-page);
  line-height: 34px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.count {
  @include heading.count-tag;
}

.lede {
  max-width: 72ch;
  color: var(--secondary);
}
</style>
