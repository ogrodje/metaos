import { defineNuxtPlugin, useRuntimeConfig } from '#imports'
import { useAuthStore } from '@/stores/auth'

// Signs the user in once the page is hydrated, so it first is as the server rendered it, for someone who isn't signed
// in; not waited for, as what's public shows in the meantime
export default defineNuxtPlugin((nuxtApp) => {
  const { issuer, clientId } = useRuntimeConfig().public
  const auth = useAuthStore()
  nuxtApp.hooks.hookOnce('app:suspense:resolve', () => {
    void auth.init({ issuer, clientId })
  })
})
