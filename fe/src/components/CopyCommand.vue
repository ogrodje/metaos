<script setup lang="ts">
import { ref } from 'vue'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faCheck } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { withTimeout } from '@/usage'

// A command to run in a terminal, or code to use, e.g. a GraphQL query: on the page's one code surface, with what it
// is and a button that copies it at the top. Long lines scroll inside it rather than widening the page.
const props = withDefaults(defineProps<{
  command: string
  // What it is, e.g. Terminal or GraphQL
  label?: string
  // For the button, e.g. Copy the query
  what?: string
}>(), { label: 'Terminal', what: 'command' })

const code = ref<HTMLElement>()
const copied = ref(false)
// What the button did, for screen readers
const status = ref('')
let timer: ReturnType<typeof setTimeout> | undefined

async function copy() {
  clearTimeout(timer)
  try {
    await withTimeout(navigator.clipboard.writeText(props.command))
    copied.value = true
    status.value = 'Copied'
  } catch (error) {
    // Selected instead, for the keyboard's copy
    console.error(`The ${props.what} was not copied`, error)
    const range = document.createRange()
    if (code.value) range.selectNodeContents(code.value)
    getSelection()?.removeAllRanges()
    getSelection()?.addRange(range)
    status.value = 'Selected, to copy with ⌘C or Ctrl+C'
  }
  timer = setTimeout(() => {
    copied.value = false
    status.value = ''
  }, 2000)
}
</script>

<template>
  <div class="code">
    <div class="bar">
      <span class="label">{{ label }}</span>
      <button type="button" class="icon-link" :aria-label="copied ? 'Copied' : `Copy the ${what}`"
              :title="copied ? 'Copied' : 'Copy'" @click="copy">
        <FontAwesomeIcon :icon="copied ? faCheck : faCopy"/>
      </button>
      <span class="visually-hidden" role="status">{{ status }}</span>
    </div>
    <pre><code ref="code">{{ command }}</code></pre>
  </div>
</template>

<style scoped lang="scss">

// Monospace on a light surface, in a hairline
.code {
  margin-top: 12px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

// What it is, and the copy button at the top right
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 4px 2px 14px;
  border-bottom: 1px solid var(--rule);

  .label {
    font-size: var(--text-caption);
    line-height: 18px;
    color: var(--muted);
  }

  button {
    flex: none;
    color: var(--secondary);
    background: none;
    border: 0;
    cursor: pointer;

    &:hover {
      color: var(--ink);
      background: var(--hover);
    }

    &:focus-visible {
      outline: 2px solid var(--accent-text);
      outline-offset: -2px;
    }
  }
}

// Scrolls on its own rather than widening the page
pre {
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: var(--text-small);
  line-height: 22px;
  color: var(--ink);
  tab-size: 2;

  code {
    font: inherit;
  }
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

</style>
