import type { RouterConfig } from 'nuxt/schema'
import { routes } from '@/router'

// The pages as src/router/index.ts lists them, rather than Nuxt's from a pages/ directory
export default {
  routes: () => routes,
} satisfies RouterConfig
