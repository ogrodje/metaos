<script setup lang="ts">
import { useRoute } from 'vue-router'
import NotFound from '@/components/NotFound.vue'

const route = useRoute()
</script>

<template>
  <main class="content">
    <NotFound title="There's no such page">
      Nothing in MetaOS is at <code>{{ route.path }}</code>. The link may be mistyped, or the page may have moved.
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
