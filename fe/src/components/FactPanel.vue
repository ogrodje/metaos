<script setup lang="ts">
// A thing's facts beside what it is, e.g. an episode's status, dates, people and files: its FactGroups, at the right of
// a page's workspace (page.scss), where it stays while the page scrolls, and above the content on narrower screens
defineProps<{ label: string }>()
</script>

<template>
  <aside class="fact-panel" :aria-label="label">
    <slot/>
  </aside>
</template>

<style scoped lang="scss">

.fact-panel {
  display: flex;
  flex-direction: column;
  font-size: 13px;
  line-height: 20px;
}

// Only while it fits on the screen, as a taller one couldn't be scrolled to its end
@media (min-width: 961px) and (min-height: 720px) {
  .fact-panel {
    position: sticky;
    top: 24px;
  }
}
</style>
