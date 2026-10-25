<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RENAME_SESSION, type Session } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'

// A recording session's name, as the team changes it on its page, in place of its heading: updateSession, as
// update_session does over MCP. Empty clears it, and the session goes by its location again, else by its date.
const props = defineProps<{ session: Pick<Session, 'id' | 'date' | 'name' | 'location'> }>()

const emit = defineEmits<{
  renamed: [session: Pick<Session, 'id' | 'name' | 'location'>]
  close: []
}>()

const draft = ref(props.session.name ?? '')
const busy = ref(false)
const error = ref<string>()
const input = ref<HTMLInputElement>()

onMounted(() => {
  input.value?.focus()
  input.value?.select()
})

async function save() {
  if (busy.value) return
  const name = draft.value.trim()
  if (name === (props.session.name ?? '')) return emit('close')
  busy.value = true
  error.value = undefined
  try {
    const { session } = await graphql<{ session: Pick<Session, 'id' | 'name' | 'location'> }>(RENAME_SESSION, { id: props.session.id, name })
    emit('renamed', session)
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="rename" @submit.prevent="save" @keydown.esc.prevent="emit('close')">
    <label for="session-name" class="label">Name</label>
    <div class="row">
      <input id="session-name" ref="input" v-model="draft" type="text" lang="sl" autocomplete="off" :readonly="busy"
             :placeholder="session.location ?? 'e.g. AWS Community Day Adria 2026'" aria-describedby="session-name-hint">
      <button type="submit" class="button primary" :aria-disabled="busy || undefined">{{ busy ? 'Saving…' : 'Save' }}</button>
      <button type="button" class="button" :disabled="busy" @click="emit('close')">Cancel</button>
    </div>
    <p id="session-name-hint" class="hint">
      Without a name, it goes by its {{ session.location ? `location, ${session.location}` : 'date' }}.
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </form>
</template>

<style scoped lang="scss">
.rename {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 680px;
}

.label {
  font-size: 13px;
  line-height: 20px;
  font-weight: 500;
  color: var(--secondary);
}

.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

// As big as the heading it stands in for
input {
  flex: 1 1 280px;
  min-width: 0;
  min-height: 44px;
  padding: 0 12px;
  font: inherit;
  font-size: 20px;
  font-weight: 600;
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 6.4px;

  &::placeholder {
    font-weight: 400;
    color: #9A9A9A;
  }

  &:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 1px;
  }
}

// Its one action, filled, as a comment's Comment is
.button.primary {
  color: #FFFFFF;
  background: var(--ink);
  border-color: var(--ink);

  &:hover {
    color: #FFFFFF;
    background: #2B2B2B;
    border-color: #2B2B2B;
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    background: var(--hover);
    border-color: var(--hairline);
    cursor: default;
  }
}

.button:disabled {
  color: var(--muted);
  cursor: default;
}

.hint {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

.error {
  font-size: 13px;
  line-height: 20px;
  color: var(--accent-text);
}

button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
