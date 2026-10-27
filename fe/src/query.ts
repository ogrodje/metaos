import { computed, type WritableComputedRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * A list page's filter or view kept in its address, e.g. /episodes?show=Bytes&view=grid, so a link to the page keeps
 * it, and the server renders it so too. [fallback] is left out of the address; anything but [allowed] reads as it.
 */
export function useQueryState<T extends string>(key: string, fallback: T, allowed?: readonly T[]): WritableComputedRef<T> {
  const route = useRoute()
  const router = useRouter()
  return computed({
    get: () => {
      const value = route.query[key]
      if (typeof value !== 'string' || (allowed && !allowed.includes(value as T))) return fallback
      return value as T
    },
    set: (value) => {
      void router.replace({ query: { ...route.query, [key]: value === fallback ? undefined : value }, hash: route.hash })
    },
  })
}

/**
 * Turns a list page's filters off at once, e.g. its Clear filters: [keys] go from the address, the rest, e.g. its view,
 * stays. One by one through useQueryState, each would replace the address the one before it hadn't changed yet.
 */
export function useClearQuery() {
  const route = useRoute()
  const router = useRouter()
  return (keys: string[]) => {
    const query = Object.fromEntries(Object.entries(route.query).filter(([key]) => !keys.includes(key)))
    void router.replace({ query, hash: route.hash })
  }
}
