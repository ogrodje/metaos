<script setup lang="ts">
import { faList, faTableCellsLarge } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'

/** How a list of episodes is shown: a line each, or a grid of their thumbnails */
export type ListView = 'list' | 'grid'

const view = defineModel<ListView>({ required: true })
</script>

<template>
  <div class="toggle" role="group" aria-label="Show as">
    <button type="button" :aria-pressed="view === 'list'" title="List" aria-label="List" @click="view = 'list'">
      <FontAwesomeIcon :icon="faList"/>
    </button>
    <button type="button" :aria-pressed="view === 'grid'" title="Grid" aria-label="Grid" @click="view = 'grid'">
      <FontAwesomeIcon :icon="faTableCellsLarge"/>
    </button>
  </div>
</template>

<style scoped lang="scss">

.toggle {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);

  button {
    display: inline-grid;
    place-items: center;
    width: var(--icon-link);
    height: var(--icon-link);
    font-size: var(--text-small);
    color: var(--muted);
    background: #FFFFFF;
    border: 0;
    cursor: pointer;

    & + button {
      border-left: 1px solid var(--hairline);
    }

    &[aria-pressed="true"] {
      color: var(--ink);
      background: var(--hover);
    }

    &:focus-visible {
      outline-offset: -2px;
    }
  }
}
</style>
