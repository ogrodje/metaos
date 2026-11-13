<script setup lang="ts">
import { faFileLines } from '@fortawesome/free-regular-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { GoogleDoc } from '@/episodes'
import { useLocale } from '@/useLocale'

defineProps<{ documents: GoogleDoc[] }>()

const { t } = useLocale()
</script>

<!-- In Google Docs blue when pointed at -->
<template>
  <a v-for="(doc, index) in documents" :key="doc.fileId" :href="doc.url" target="_blank" rel="noopener"
     class="icon-link brand-link" style="--brand: #4285F4"
     :title="documents.length > 1 ? t('files.docN', { n: index + 1 }) : t('files.doc')"
     :aria-label="documents.length > 1 ? t('files.docN', { n: index + 1 }) : t('files.doc')">
    <FontAwesomeIcon :icon="faFileLines"/>
  </a>
</template>
