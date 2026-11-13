<script setup lang="ts">
import { faDropbox } from '@fortawesome/free-brands-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { DropboxFolderLink } from '@/episodes'
import { useLocale } from '@/useLocale'

defineProps<{ folders: DropboxFolderLink[] }>()

const { t } = useLocale()
</script>

<!-- In Dropbox blue when pointed at -->
<template>
  <a v-for="folder in folders" :key="folder.url" :href="folder.url" target="_blank" rel="noopener"
     class="icon-link brand-link" style="--brand: #0061FF" :title="`Dropbox: ${folder.path}`"
     :aria-label="t('files.dropboxFolder', { path: folder.path })">
    <FontAwesomeIcon :icon="faDropbox"/>
  </a>
</template>
