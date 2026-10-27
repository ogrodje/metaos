<script setup lang="ts">
import { faMagnifyingGlass } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'

// A list page's filters, under its heading: a box that finds in the list as you type, its ChipGroups, and at the end,
// e.g., how the list is shown (ViewToggle)
defineProps<{ placeholder: string }>()

const find = defineModel<string>('find', { required: true })
</script>

<template>
  <div class="filter-bar">
    <label class="find">
      <FontAwesomeIcon :icon="faMagnifyingGlass" class="icon"/>
      <span class="visually-hidden">{{ placeholder }}</span>
      <input v-model="find" type="search" :placeholder="placeholder" autocomplete="off" spellcheck="false">
    </label>
    <slot/>
    <div v-if="$slots.end" class="end"><slot name="end"/></div>
  </div>
</template>

<style scoped lang="scss">

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--hairline);
}

.find {
  display: flex;
  flex: 0 1 300px;
  align-items: center;
  gap: 8px;
  min-width: 220px;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--hairline);
  border-radius: 6.4px;
  cursor: text;

  &:focus-within {
    border-color: #A3A3A3;
    outline: 2px solid var(--accent-text);
    outline-offset: 1px;
  }

  .icon {
    font-size: 12px;
    color: var(--muted);
  }

  input {
    flex: 1;
    min-width: 0;
    padding: 0;
    font: inherit;
    font-size: 14px;
    color: var(--ink);
    background: none;
    border: 0;
    outline: 0;

    &::placeholder {
      color: var(--muted);
    }
  }
}

.end {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  margin-left: auto;
}

@media (max-width: 640px) {
  .find {
    flex: 1 1 100%;
    min-width: 0;
  }

  .end {
    margin-left: 0;
  }
}
</style>
