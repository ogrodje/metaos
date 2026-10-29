<script setup lang="ts">
// An episode's show, with a dot in its colour; grey for a show without one
defineProps<{ name: string, color: string | null }>()
</script>

<template>
  <span class="show">
    <span class="dot" :style="{ background: color ?? undefined }" aria-hidden="true"/>{{ name }}
  </span>
</template>

<style scoped lang="scss">

.show {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 500;
  color: var(--secondary);
  white-space: nowrap;
}

.dot {
  width: 6px;
  height: 6px;
  background: var(--muted);
  border-radius: 50%;
}

</style>
