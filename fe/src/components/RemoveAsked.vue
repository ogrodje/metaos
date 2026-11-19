<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'
import { errorMessage } from '@/graphql'
import { useLocale } from '@/useLocale'

// Remove, as a quiet button that asks where it is (Remove it? Remove, Cancel) before it does, as the media's rows do;
// what it removes says why it didn't
const props = defineProps<{
  // The question, e.g. Remove the cover?
  ask: string
  remove: () => Promise<void>
}>()

const emit = defineEmits<{
  removed: []
}>()

const { t } = useLocale()
const id = useId()

const asking = ref(false)
const busy = ref(false)
const error = ref<string>()

function askToRemove() {
  error.value = undefined
  asking.value = true
  void nextTick(() => document.getElementById(`${id}-keep`)?.focus())
}

function keep() {
  asking.value = false
  void nextTick(() => document.getElementById(`${id}-remove`)?.focus())
}

async function remove() {
  if (busy.value) return
  busy.value = true
  try {
    await props.remove()
    asking.value = false
    emit('removed')
  } catch (e) {
    error.value = t('removeAsked.failed', { error: errorMessage(e, t) })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <span class="remove-asked">
    <span v-if="asking" class="confirm" role="group" :aria-labelledby="`${id}-ask`" @keydown.esc="keep">
      <span :id="`${id}-ask`" class="question">{{ ask }}</span>
      <button type="button" class="button small delete" :aria-disabled="busy || undefined" @click="remove">
        {{ busy ? t('removeAsked.removing') : t('common.remove') }}
      </button>
      <button :id="`${id}-keep`" type="button" class="button small" :disabled="busy" @click="keep">{{ t('common.cancel') }}</button>
    </span>
    <button v-else :id="`${id}-remove`" type="button" class="text-button" @click="askToRemove">{{ t('common.remove') }}</button>
    <span v-if="error" class="error" role="alert">{{ error }}</span>
  </span>
</template>

<style scoped lang="scss">
.remove-asked {
  display: grid;
  gap: 4px;
  justify-items: start;
  font-size: var(--text-caption);
  line-height: 16px;
}

.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.question {
  font-weight: 500;
}

.text-button {
  font-size: var(--text-caption);
  color: var(--muted);

  &:hover {
    color: var(--ink);
  }
}

.button.delete {
  color: var(--accent-text);
}

.button[aria-disabled='true'] {
  color: var(--muted);
  cursor: default;
}

.error {
  color: var(--accent-text);
}
</style>
