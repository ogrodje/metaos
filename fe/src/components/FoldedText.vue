<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import MarkdownText from '@/components/MarkdownText.vue'

// Longer than this, a text shows its first lines until it's opened, so what comes after it is in reach
const FOLD_WORDS = 120

// A section of Markdown, e.g. an episode's summary: folded to its first lines when it's long, with how long it is. All
// of it is in the page, the server's too, for search engines; only the screen shows less of it.
const props = defineProps<{
  title: string
  source: string
}>()

const id = useId()
const open = ref(false)

const words = computed(() => props.source.split(/\s+/).filter(Boolean).length)
const paragraphs = computed(() => props.source.split(/\n\s*\n/).filter((part) => part.trim()).length)
const long = computed(() => words.value > FOLD_WORDS)
const size = computed(() => [
  paragraphs.value > 1 ? `${paragraphs.value} paragraphs` : null,
  `${words.value.toLocaleString('en-GB')} words`,
].filter(Boolean).join(', '))
</script>

<template>
  <section :aria-labelledby="`${id}-title`">
    <div class="heading">
      <h2 :id="`${id}-title`">{{ title }}</h2>
      <span v-if="long" class="note size">{{ size }}</span>
    </div>
    <MarkdownText :id="`${id}-text`" :class="{ folded: long && !open }" :source="source" lang="sl"/>
    <button v-if="long" type="button" class="text-button fold" :aria-expanded="open" :aria-controls="`${id}-text`" @click="open = !open">
      {{ open ? 'Fold it' : 'Read all of it' }}
    </button>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.size {
  margin-left: auto;
}

// The first lines, fading out
.folded {
  max-height: 160px;
  overflow: hidden;
  mask-image: linear-gradient(#000 55%, transparent);
}

.fold {
  margin-top: 8px;
}
</style>
