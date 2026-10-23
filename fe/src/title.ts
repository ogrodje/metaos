import { onBeforeUnmount, ref, watchEffect } from 'vue'

// The browser tab's and history's name for a page, e.g. "Poletna vročica · Ogrodje MetaOS"

const NAME = 'Ogrodje MetaOS'

/** The page's own name, without MetaOS's, for the header to announce after going somewhere; empty until it's known */
export const pageName = ref('')

export function setPageTitle(name: string | undefined) {
  pageName.value = name ?? ''
  document.title = name ? `${name} · ${NAME}` : NAME
}

/**
 * Names the page after what it shows, once that's loaded, e.g. an episode's title. The route's own title (its
 * meta.title) stands until then, and for pages that don't change it.
 */
export function usePageTitle(name: () => string | undefined) {
  let active = true
  watchEffect(() => {
    const value = name()
    if (active && value) setPageTitle(value)
  })
  onBeforeUnmount(() => {
    active = false
  })
}
