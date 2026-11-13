<script setup lang="ts">
import { I18nT } from 'vue-i18n'
import { useRoute } from 'vue-router'
import NotFound from '@/components/NotFound.vue'
import { useLocale } from '@/useLocale'

const route = useRoute()
const { t } = useLocale()
</script>

<template>
  <main class="content">
    <NotFound :title="t('ui.notFoundTitle')">
      <I18nT keypath="ui.notFound" scope="global"><template #path><code>{{ route.path }}</code></template></I18nT>
    </NotFound>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

code {
  font-family: var(--font-mono);
  font-size: var(--text-small);
  overflow-wrap: anywhere;
}
</style>
