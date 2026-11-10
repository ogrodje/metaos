import { watch } from 'vue'
import { InMemoryStorageProvider, type IToggle, UnleashClient } from 'unleash-proxy-client'
import { defineNuxtPlugin, onNuxtReady } from '#imports'
import { asToggles, flagsOn } from '@/flags'
import { useFlagsState } from '@/flagState'
import { useAuthStore } from '@/stores/auth'

// The front-end's feature flags (flags.ts), from Unleash through the front-end's own /flags (server/routes/flags), which
// asks it with its token: on the server as someone who isn't signed in, which the page comes with, then in the browser,
// for whoever's signed in, every 30 s. While Unleash can't say, they're as it last said, else their defaults.
export default defineNuxtPlugin(async () => {
  const state = useFlagsState()
  if (import.meta.server) {
    try {
      const answer = await $fetch<{ toggles: IToggle[] }>('/flags', { query: { appName: 'metaos-web' } })
      state.value = flagsOn(answer.toggles)
    } catch {
      // Unleash is off or down: their defaults
    }
    return
  }

  const client = new UnleashClient({
    url: new URL('/flags', location.origin),
    // Not a token: the server asks Unleash with its own, which the browser never sees
    clientKey: 'metaos-web',
    appName: 'metaos-web',
    refreshInterval: 30,
    // Nothing kept in the browser, as with New Relic's agent (newrelic.ts)
    storageProvider: new InMemoryStorageProvider(),
    bootstrap: asToggles(state.value),
  })
  const auth = useAuthStore()
  client.on('update', () => {
    state.value = flagsOn(client.getAllToggles())
  })

  // Once the page is hydrated, as the server rendered it; then for whoever's signed in, and whether they're on the
  // team, which a flag's strategy can ask for (context field team)
  onNuxtReady(() => {
    void client.start()
    watch(() => [auth.userId, auth.team] as const, ([userId, team]) => {
      void client.updateContext({ userId, properties: { team: String(team) } })
    })
  })
})
