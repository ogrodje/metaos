import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useHead } from '#imports'

// The browser tab's and history's name for a page, e.g. "Poletna vročica · Ogrodje MetaOS", also in the page the
// server renders, for search engines and link previews

const NAME = 'Ogrodje MetaOS'

/** The page's own name, without MetaOS's, for the header to announce after going somewhere; empty until it's known */
export const pageName = ref('')

/** A page's whole title, from its own name: the header's titleTemplate (App.vue) */
export function fullTitle(name?: string | null) {
  return name ? `${name} · ${NAME}` : NAME
}

/**
 * Says [name] for screen readers, in the header. Only in the browser: on the server this is shared by every request,
 * and its page doesn't say it.
 */
export function announcePage(name: string | undefined) {
  if (import.meta.client) pageName.value = name ?? ''
}

/**
 * Names the page after what it shows, once that's loaded, e.g. an episode's title. The route's own title (its
 * meta.title) stands until then, and for pages that don't change it.
 */
export function usePageTitle(name: () => string | undefined) {
  const route = useRoute()
  const title = () => name() ?? route.meta.title
  useHead({ title, meta: [{ property: 'og:title', content: title }] })

  // Announced once the page is there, rather than while it's hydrated, which has to match the server's
  let active = false
  onMounted(() => {
    active = true
    if (name()) announcePage(name())
  })
  watch(name, (value) => {
    if (active && value) announcePage(value)
  })
  onBeforeUnmount(() => {
    active = false
  })
}
