<script setup lang="ts">
import { ref } from 'vue'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faCheck } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'

// A command to run in a terminal, with a button that copies it
const props = defineProps<{ command: string }>()

const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(props.command)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch (error) {
    console.error('The command was not copied', error)
  }
}
</script>

<template>
  <div class="command">
    <code>{{ command }}</code>
    <button type="button" class="icon-link" :aria-label="copied ? 'Copied' : 'Copy the command'"
            :title="copied ? 'Copied' : 'Copy'" @click="copy">
      <FontAwesomeIcon :icon="copied ? faCheck : faCopy"/>
    </button>
  </div>
</template>

<style scoped lang="scss">

.command {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 4px 4px 4px 14px;
  background: var(--hover);
  border: 1px solid var(--hairline);
  border-radius: 8px;

  // Scrolls on its own rather than widening the page
  code {
    flex: 1;
    overflow-x: auto;
    padding: 5px 0;
    font-family: var(--font-mono);
    font-size: 13px;
    line-height: 22px;
    white-space: nowrap;
    color: var(--ink);
  }

  button {
    flex: none;
    color: var(--secondary);
    background: none;
    border: 0;
    cursor: pointer;

    &:hover {
      background: #FFFFFF;
    }
  }
}

</style>
