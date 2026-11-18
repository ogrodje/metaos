import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EVENTS_REFRESH, type EventsRefresh, REFRESH_EVENTS } from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'

// How often a page asks whether the refresh it started is still on
const POLL_EVERY = 5000

/**
 * The team's Refresh events, of every organisation's or of [organisation]'s: refreshEvents starts it, unless it's on
 * already, and the page asks every few seconds whether it still is, as it goes on after the answer; [done] once it
 * isn't. Its events come as they're found, through the live updates. MetaOS without Temporal, e.g. a preview, says
 * why it can't (error).
 */
export function useEventsRefresh(organisation: () => string | null, done: () => unknown) {
  const auth = useAuthStore()
  const { t } = useLocale()
  const running = ref(false)
  const starting = ref(false)
  const error = ref<string>()
  let polling: ReturnType<typeof setTimeout> | undefined
  let mounted = false

  function poll() {
    clearTimeout(polling)
    if (mounted && running.value) polling = setTimeout(() => void check(), POLL_EVERY)
  }

  async function check() {
    if (!auth.team) return
    try {
      const { refresh } = await graphql<{ refresh: EventsRefresh }>(EVENTS_REFRESH, { organisation: organisation() })
      const was = running.value
      running.value = refresh.running
      if (was && !refresh.running) await done()
    } catch {
      // Asked again while it's on; a refresh that ended unseen shows in the events themselves
    }
    poll()
  }

  async function start() {
    if (starting.value || running.value) return
    starting.value = true
    error.value = undefined
    try {
      const { refresh } = await graphql<{ refresh: EventsRefresh }>(REFRESH_EVENTS, { organisation: organisation() })
      running.value = refresh.running
      poll()
    } catch (e) {
      error.value = errorMessage(e, t)
    } finally {
      starting.value = false
    }
  }

  onMounted(() => {
    mounted = true
    watch(() => auth.team, (team) => team && void check(), { immediate: true })
  })
  onBeforeUnmount(() => {
    mounted = false
    clearTimeout(polling)
  })

  return { running, starting, error, start }
}
