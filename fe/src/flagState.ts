import { computed } from 'vue'
import { useState } from '#imports'
import { type Flag, type FlagsState, isOn } from '@/flags'

/** The flags that are on, the server's in the page's payload, then the browser's (plugins/flags.ts) */
export function useFlagsState() {
  return useState<FlagsState>('flags', () => null)
}

/** Whether [flag] is on, as it changes */
export function useFlag(flag: Flag) {
  const state = useFlagsState()
  return computed(() => isOn(state.value, flag))
}
