<script setup lang="ts">
import { faXmark } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'

// A filter that's on without chips of its own, e.g. the stage the home page led to: pressed, as a ChipGroup's chosen
// chip is, and pressing it turns it off
defineProps<{
  label: string
  value: string
}>()
defineEmits<{ clear: [] }>()
</script>

<template>
  <button type="button" class="filter-chip" :aria-label="`${label}: ${value}. Remove this filter`" @click="$emit('clear')">
    <span class="label">{{ label }}</span>{{ value }}<FontAwesomeIcon :icon="faXmark" class="icon"/>
  </button>
</template>

<style scoped lang="scss">

.filter-chip {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: var(--text-small);
  line-height: 20px;
  color: #FFFFFF;
  white-space: nowrap;
  background: var(--ink);
  border: 1px solid var(--ink);
  border-radius: 99px;
  cursor: pointer;

  &:hover .icon {
    color: #FFFFFF;
  }
}

.label {
  color: rgba(255, 255, 255, 0.7);
}

.icon {
  font-size: var(--text-micro);
  color: rgba(255, 255, 255, 0.7);
}
</style>
