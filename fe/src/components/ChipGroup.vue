<script setup lang="ts" generic="T extends string">
// One of a few choices as chips, each with how many it has, e.g. a show or who recommended an idea; the one chosen is
// pressed. In a FilterBar, and above a feed (ActivityFeed).
defineProps<{
  label: string
  options: { value: T, title: string, count?: number, color?: string | null }[]
}>()

const model = defineModel<T>({ required: true })
</script>

<template>
  <div class="chips" role="group" :aria-label="label">
    <button v-for="option in options" :key="option.value" type="button" class="chip" :aria-pressed="model === option.value"
            @click="model = option.value">
      <span v-if="option.color" class="swatch" :style="{ background: option.color }" aria-hidden="true"/>
      {{ option.title }}<span v-if="option.count !== undefined" class="count">{{ option.count }}</span>
    </button>
  </div>
</template>

<style scoped lang="scss">

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);
  white-space: nowrap;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 99px;
  cursor: pointer;

  &:hover {
    border-color: #A3A3A3;
  }

  &[aria-pressed="true"] {
    color: #FFFFFF;
    background: var(--ink);
    border-color: var(--ink);

    .count {
      color: rgba(255, 255, 255, 0.7);
    }
  }
}

.count {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.swatch {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

// On phones a row that scrolls sideways rather than wrapping into several
@media (max-width: 640px) {
  .chips {
    flex-wrap: nowrap;
    max-width: calc(100% + 32px);
    margin: 0 -16px;
    padding: 0 16px;
    overflow-x: auto;
    scrollbar-width: none;
  }
}
</style>
