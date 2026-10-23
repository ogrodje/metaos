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
