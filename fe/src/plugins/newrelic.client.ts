import { defineNuxtPlugin, useRuntimeConfig } from '#imports'
import { browserAgentOptions } from '@/newrelic'

// New Relic's browser agent (newrelic.ts), when it has its settings: loaded as the app starts, not waited for, so it
// doesn't hold the page back
export default defineNuxtPlugin((nuxtApp) => {
  const options = browserAgentOptions(useRuntimeConfig().public.newRelic)
  if (!options) return
  const agent = import('@newrelic/browser-agent/loaders/browser-agent')
    .then(({ BrowserAgent }) => new BrowserAgent(options))
  // Vue catches what its components throw, and only logs it, so the agent wouldn't see it as an error
  const notice = (error: unknown) => {
    void agent.then((it) => it.noticeError(error instanceof Error ? error : String(error)))
  }
  nuxtApp.hook('vue:error', notice)
  nuxtApp.hook('app:error', notice)
})
