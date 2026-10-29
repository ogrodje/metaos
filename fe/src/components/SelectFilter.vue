<script setup lang="ts" generic="T extends string">
// One of many choices as a select, with its label, in a FilterBar where they'd be too many chips (ChipGroup), e.g. who
// recommended an idea, or how a list is grouped
defineProps<{
  label: string
  options: { value: T, title: string, count?: number }[]
}>()

const model = defineModel<T>({ required: true })
</script>

<template>
  <label class="select-filter">{{ label }}
    <select v-model="model" class="input small">
      <option v-for="option in options" :key="option.value" :value="option.value">
        {{ option.title }}{{ option.count !== undefined ? ` (${option.count})` : '' }}
      </option>
    </select>
  </label>
</template>

<style scoped lang="scss">

.select-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;

  select {
    max-width: 220px;
  }
}
</style>
