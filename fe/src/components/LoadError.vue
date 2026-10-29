<script setup lang="ts">
// What didn't load and why, in place of it, and a way to try again without reloading the page
defineProps<{
  // What it is, e.g. "The episodes"
  what: string
  error: string
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="load-error" role="alert">
    <p>{{ what }} didn't load: {{ error }}</p>
    <button type="button" class="button" @click="$emit('retry')">Try again</button>
  </div>
</template>

<style scoped lang="scss">

.load-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);

  p {
    flex: 1 1 320px;
    max-width: 70ch;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
  }
}

</style>
