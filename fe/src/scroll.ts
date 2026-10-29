import { type InjectionKey, onBeforeUnmount, onMounted, type Ref, ref, watch } from 'vue'

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

/** Where a side panel that's taller than the screen is: stuck at its top or its bottom, or scrolling along between */
type Stick = 'top' | 'bottom' | 'free'

/** What's in a side panel may ask it to show a part of it, e.g. the comment a link leads to (useStickySide) */
export const SIDE_PANEL: InjectionKey<{ reveal: (element: HTMLElement) => void }> = Symbol('side panel')

/**
 * Keeps [panel], a side panel, in view as the page scrolls, however tall it is, and without a scrollbar of its own, as
 * long sidebars do: one that fits stays [gap] from the top; a taller one scrolls along with the page until its end
 * comes into view, then stays, its bottom [gap] from the screen's, and scrolling back up, the same with its top. It
 * says which in data-stick (top, bottom, or free while it scrolls along) and how far in --stick-top, which its styles
 * use where it's [beside] the page (a media query), not above it. Its place in the page is the top of its parent, e.g.
 * of its grid area. Free, it's where the page puts it, so it scrolls with the page as smoothly as the rest; only at its
 * ends does it stick, which the browser does too.
 */
export function useStickySide(panel: Ref<HTMLElement | undefined>, enabled: () => boolean, beside: string, gap = 24) {
  let lastY = 0
  let stick: Stick = 'top'
  let top = gap

  function set(element: HTMLElement, to: Stick, at: number) {
    if (to === stick && at === top && element.dataset.stick === to) return
    stick = to
    top = at
    element.dataset.stick = to
    element.style.setProperty('--stick-top', `${at}px`)
  }

  function active(element: HTMLElement | undefined): element is HTMLElement {
    return !!element && enabled() && window.matchMedia(beside).matches
  }

  // Where it is from its place in the page, to scroll along from there
  const offset = (element: HTMLElement) =>
    element.getBoundingClientRect().top - element.parentElement!.getBoundingClientRect().top

  function place() {
    const element = panel.value
    const y = window.scrollY
    const down = y > lastY
    const up = y < lastY
    lastY = y
    if (!active(element)) return
    const height = element.offsetHeight
    const screen = window.innerHeight
    if (height + 2 * gap <= screen) return set(element, 'top', gap)
    const box = element.getBoundingClientRect()
    if (down) {
      if (box.bottom <= screen - gap) set(element, 'bottom', screen - gap - height)
      else if (stick === 'top') set(element, 'free', offset(element))
    } else if (up) {
      if (box.top >= gap) set(element, 'top', gap)
      else if (stick === 'bottom') set(element, 'free', offset(element))
    } else if (stick === 'bottom') {
      // Its height or the screen's changed
      set(element, 'bottom', screen - gap - height)
    }
  }

  /** Scrolls the page to [element] in the panel, the panel along with it, and no further than the panel's end */
  function reveal(element: HTMLElement) {
    const side = panel.value
    if (!active(side)) return element.scrollIntoView({ block: 'start' })
    set(side, 'free', offset(side))
    const end = side.getBoundingClientRect().bottom + window.scrollY + gap - window.innerHeight
    window.scrollTo({ top: Math.max(0, Math.min(element.getBoundingClientRect().top + window.scrollY - gap, end)) })
  }

  let resized: ResizeObserver | undefined

  onMounted(() => {
    lastY = window.scrollY
    window.addEventListener('scroll', place, { passive: true })
    window.addEventListener('resize', place)
    resized = new ResizeObserver(place)
    watch(panel, (element, previous) => {
      if (previous) resized?.unobserve(previous)
      if (element) {
        set(element, 'top', gap)
        resized?.observe(element)
      }
    }, { immediate: true })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', place)
    window.removeEventListener('resize', place)
    resized?.disconnect()
  })

  return { reveal }
}
