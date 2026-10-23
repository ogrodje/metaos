<script setup lang="ts">
// A list page's heading: its name, how many it has, and a line about it, e.g. for visitors what they see
defineProps<{
  title: string
  count?: number
}>()
</script>

<template>
  <header class="page-header">
    <div class="name">
      <h1>{{ title }}</h1>
      <span v-if="count !== undefined" class="count">{{ count }}</span>
    </div>
    <p v-if="$slots.default" class="lede"><slot/></p>
  </header>
</template>

<style scoped lang="scss">

.page-header {
  display: grid;
  gap: 6px;
}

.name {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

h1 {
  font-size: 24px;
  line-height: 32px;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.count {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

.lede {
  max-width: 72ch;
  color: var(--secondary);
}
</style>
