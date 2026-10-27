<script setup lang="ts">
// What a list page says when its filters leave nothing: what was looked for, and a way back to the whole list. The slot
// is the start of the sentence, e.g. No episodes match.
defineProps<{ find: string }>()
defineEmits<{ clear: [] }>()
</script>

<template>
  <p class="no-match">
    <slot/><template v-if="find.trim()"> “<span lang="sl">{{ find.trim() }}</span>”</template>.
    <button type="button" @click="$emit('clear')">Clear filters</button>
  </p>
</template>

<style scoped lang="scss">

.no-match {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

button {
  margin-left: 4px;
  padding: 0;
  font-weight: 500;
  color: var(--accent-text);
  background: none;
  border: 0;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}
</style>
