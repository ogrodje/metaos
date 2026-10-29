<script setup lang="ts">
import { provide, ref } from 'vue'
import { SIDE_PANEL, useStickySide } from '@/scroll'

// A thing's facts beside what it is, e.g. an episode's status, dates, people and files: its FactGroups, at the right of
// a page's workspace (page.scss), where it stays while the page scrolls, and above the content on narrower screens.
// Tall, it stays in view however tall it is, e.g. an episode's, with its activity: it scrolls along with the page till
// its end is in view, and back till its top is (useStickySide), without a scrollbar of its own.
const props = defineProps<{
  label: string
  tall?: boolean
}>()

const aside = ref<HTMLElement>()
const { reveal } = useStickySide(aside, () => !!props.tall, '(min-width: 961px)')
provide(SIDE_PANEL, { reveal })
</script>

<template>
  <aside ref="aside" class="fact-panel" :class="{ tall }" :aria-label="label">
    <slot/>
  </aside>
</template>

<style scoped lang="scss">

.fact-panel {
  display: flex;
  flex-direction: column;
  font-size: var(--text-small);
  line-height: 20px;
}

// Only while it fits on the screen, as a taller one couldn't be scrolled to its end
@media (min-width: 961px) and (min-height: 720px) {
  .fact-panel {
    position: sticky;
    top: 24px;
  }
}

// Stuck at its top or its bottom, or, free, where the page puts it, scrolling along (useStickySide)
@media (min-width: 961px) {
  .fact-panel.tall {
    position: sticky;
    top: var(--stick-top, 24px);

    &[data-stick="free"] {
      position: relative;
    }
  }
}
</style>
