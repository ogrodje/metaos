import { defineNuxtPlugin, onNuxtReady, useNuxtApp, useSwitchLocalePath } from '#imports'
import { useRouter } from 'vue-router'
import { chosenLocale } from '@/localeChoice'

// The language a visitor chose (LanguageSwitch.vue), kept in this browser, and applied once the page is hydrated: the
// server renders a page in the language its address says (OG-58), as Cloudflare keeps pages by their address, so a
// Slovenian address opens in Slovenian and, after hydration, goes to the same page in English for whoever chose it

export default defineNuxtPlugin(() => {
  const nuxtApp = useNuxtApp()
  const router = useRouter()
  const switchLocalePath = useSwitchLocalePath()
  onNuxtReady(() => {
    const chosen = chosenLocale()
    const locales = nuxtApp.$i18n.availableLocales as string[]
    if (!chosen || !locales.includes(chosen) || chosen === nuxtApp.$i18n.locale.value) return
    const path = switchLocalePath(chosen as never)
    if (path) void router.replace(path)
  })
})
