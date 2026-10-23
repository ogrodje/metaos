import { config } from '@fortawesome/fontawesome-svg-core'
import { defineNuxtPlugin } from '#imports'

// Font Awesome's CSS comes with the bundle (nuxt.config.ts), rather than being added at runtime, so icons don't show
// unstyled first, also in the server's HTML
export default defineNuxtPlugin(() => {
  config.autoAddCss = false
})
