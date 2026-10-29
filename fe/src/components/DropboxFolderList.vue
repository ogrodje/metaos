<script setup lang="ts">
import { faFolder } from '@fortawesome/free-regular-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { DropboxFolder } from '@/episodes'

// Each opens in Dropbox's web app; a missing one, gone from its shared folder, is only named
defineProps<{ folders: DropboxFolder[] }>()
</script>

<template>
  <ul class="folders">
    <li v-for="folder in folders" :key="folder.url">
      <component :is="folder.missing ? 'span' : 'a'" v-bind="folder.missing ? {} : { href: folder.url, target: '_blank', rel: 'noopener' }"
                 class="folder">
        <FontAwesomeIcon :icon="faFolder" class="icon"/>
        <span v-if="folder.placeName">{{ folder.placeName }}</span>
        <span class="path">{{ folder.path }}</span>
        <span v-if="folder.missing" class="missing">missing</span>
      </component>
    </li>
  </ul>
</template>

<style scoped lang="scss">

.folders {
  padding: 0;
  list-style: none;

  .folder {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: baseline;
    column-gap: 8px;
  }

  a:hover .path {
    color: var(--accent-text);
  }

  .icon {
    color: var(--muted);
  }

  .path {
    font-family: var(--font-mono);
    font-size: var(--text-small);
    color: var(--secondary);
  }

  .missing {
    font-size: var(--text-caption);
    color: var(--muted);
  }
}

</style>
