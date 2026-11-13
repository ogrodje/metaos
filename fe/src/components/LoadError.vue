<script setup lang="ts">
import { errorMessage } from '@/graphql'
import { useLocale } from '@/useLocale'

// What didn't load and why, in place of it, and a way to try again without reloading the page
defineProps<{
  // What it is, in the page's language, e.g. "The episodes", "Epizode"
  what: string
  // Why: what was thrown, said in the page's language when it's a UserError (graphql.ts), or already said
  error: unknown
}>()

const { t } = useLocale()

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="load-error" role="alert">
    <p>{{ t('ui.loadError', { what, error: errorMessage(error, t) }) }}</p>
    <button type="button" class="button" @click="$emit('retry')">{{ t('common.tryAgain') }}</button>
  </div>
</template>

<style scoped lang="scss">

.load-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  padding: 12px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);

  p {
    flex: 1 1 320px;
    max-width: 70ch;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
  }
}

</style>
