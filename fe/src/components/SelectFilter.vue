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
    <select v-model="model">
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
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;

  select {
    height: 32px;
    max-width: 220px;
    padding: 0 8px;
    font: inherit;
    font-size: 13px;
    color: var(--ink);
    background: #FFFFFF;
    border: 1px solid var(--hairline);
    border-radius: 6.4px;
    cursor: pointer;

    &:hover {
      border-color: #A3A3A3;
    }
  }
}
</style>
