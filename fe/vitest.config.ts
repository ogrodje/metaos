import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// The unit tests (src/**/__tests__): plain modules, without Nuxt, so they run on their own
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    include: ['src/**/__tests__/*.spec.ts'],
  },
})
