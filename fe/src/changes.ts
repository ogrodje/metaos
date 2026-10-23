import { onBeforeUnmount, onMounted } from 'vue'

/** Called with a table whose rows changed, or '*' when any may have */
export type ChangeListener = (table: string) => void

// EventSource's readyState once it has given up
const CLOSED = 2

/**
 * What changed in MetaOS, as /events says it (Changes.kt): one stream for the whole app, opened when the first page
 * listens. It says '*' when changes may have gone unsaid: after the stream broke, e.g. while MetaOS restarted, and when
 * MetaOS's own connection to Postgres broke.
 */
export function createChangeFeed(url = '/events', retry = { first: 1000, max: 30_000 }) {
  const listeners = new Set<ChangeListener>()
  let source: EventSource | undefined
  let opened = false
  let failures = 0

  function tell(table: string) {
    listeners.forEach((listener) => listener(table))
  }

  function connect() {
    const current = new EventSource(url)
    source = current
    current.addEventListener('open', () => {
      if (opened) tell('*')
      opened = true
      failures = 0
    })
    current.addEventListener('change', (event) => tell((event as MessageEvent<string>).data))
    current.addEventListener('resync', () => tell('*'))
    current.addEventListener('error', () => {
      // EventSource connects again by itself when the stream breaks, but not when the answer is an error, e.g. a 502
      // while MetaOS restarts: then it gives up, and this tries again, less often each time
      if (current.readyState !== CLOSED) return
      failures++
      setTimeout(connect, Math.min(retry.first * 2 ** (failures - 1), retry.max))
    })
  }

  return {
    /** Calls [listener] with each change, until the function it returns is called */
    listen(listener: ChangeListener): () => void {
      listeners.add(listener)
      if (!source) connect()
      return () => {
        listeners.delete(listener)
      }
    },
    /** Says '*' to the listeners, as when changes may have gone unsaid */
    resync() {
      tell('*')
    },
  }
}

/**
 * Calls [refresh] after a change, once for a burst of them: [delay] after the first, and once more after it's done if
 * more came meanwhile. While the page is hidden it only notes them, and refreshes once it's shown again; also after it
 * was hidden for [staleAfter], for what MetaOS isn't told about, e.g. new photos in Koofr.
 */
export function createRefresher(
  refresh: () => unknown,
  { delay = 500, staleAfter = 60_000, now = Date.now } = {},
) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let again = false
  let hiddenSince: number | undefined
  let missed = false
  let disposed = false

  function changed() {
    if (disposed) return
    if (hiddenSince !== undefined) missed = true
    else if (running) again = true
    else timer ??= setTimeout(run, delay)
  }

  async function run() {
    timer = undefined
    running = true
    try {
      await refresh()
    } catch (error) {
      console.error('The page did not load again', error)
    } finally {
      running = false
      if (again) {
        again = false
        changed()
      }
    }
  }

  return {
    changed,
    hide() {
      hiddenSince ??= now()
    },
    show() {
      if (hiddenSince === undefined) return
      const stale = missed || now() - hiddenSince >= staleAfter
      hiddenSince = undefined
      missed = false
      if (stale) changed()
    },
    dispose() {
      disposed = true
      clearTimeout(timer)
    },
  }
}

let feed: ReturnType<typeof createChangeFeed> | undefined

/**
 * Has the pages' live updates load their data again, as anything may have changed, e.g. since the server rendered the
 * page, which may have been a while ago (plugins/rendered.ts)
 */
export function resync() {
  feed?.resync()
}

/**
 * Loads a page's data again with [refresh] when MetaOS's data changes, so the page shows it without being reloaded.
 * [refresh] should keep showing what's there until the new data is (keepUnchanged), and leave it be if it fails.
 */
export function useLiveRefresh(refresh: () => unknown) {
  const refresher = createRefresher(refresh)
  const visibility = () => (document.hidden ? refresher.hide() : refresher.show())
  let stop: (() => void) | undefined

  onMounted(() => {
    visibility()
    document.addEventListener('visibilitychange', visibility)
    stop = (feed ??= createChangeFeed()).listen(refresher.changed)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', visibility)
    stop?.()
    refresher.dispose()
  })
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * [next], with what's equal to [previous] in it kept as it was, also [previous] itself: Vue then updates only what
 * changed, and components keep their state, e.g. which photos they show. Items in lists are matched by ID when they
 * have one, so a new one at the top doesn't change those after it.
 */
export function keepUnchanged<T>(previous: unknown, next: T): T {
  if (previous === next) return next
  if (Array.isArray(previous) && Array.isArray(next)) {
    const byId = new Map(previous.filter((item) => isObject(item) && item.id !== undefined).map((item) => [item.id, item]))
    const items = next.map((item, index) =>
      keepUnchanged(isObject(item) && item.id !== undefined ? byId.get(item.id) : previous[index], item))
    const same = items.length === previous.length && items.every((item, index) => item === previous[index])
    return (same ? previous : items) as T
  }
  if (isObject(previous) && isObject(next)) {
    const keys = Object.keys(next)
    const merged = Object.fromEntries(keys.map((key) => [key, keepUnchanged(previous[key], next[key])]))
    const same = keys.length === Object.keys(previous).length
      && keys.every((key) => key in previous && merged[key] === previous[key])
    return (same ? previous : merged) as T
  }
  return next
}
