import { onBeforeUnmount, onMounted, type Ref, ref, watch } from 'vue'

/**
 * Whether the page has scrolled past [target], e.g. a page's title, which is then above the screen: for something
 * that says it again where it's still in view, e.g. a FactPanel's PanelTitle. False while [target] isn't there, and on
 * the server, which doesn't scroll.
 */
export function useScrolledPast(target: Ref<HTMLElement | undefined>) {
  const past = ref(false)
  let observer: IntersectionObserver | undefined

  onMounted(() => {
    observer = new IntersectionObserver(([entry]) => {
      past.value = !!entry && !entry.isIntersecting && entry.boundingClientRect.bottom < 0
    })
    watch(target, (element, previous) => {
      if (previous) observer?.unobserve(previous)
      if (element) observer?.observe(element)
      else past.value = false
    }, { immediate: true })
  })
  onBeforeUnmount(() => observer?.disconnect())

  return past
}
