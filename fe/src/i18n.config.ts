import { defineI18nConfig } from '#imports'
import { slovenianPlural } from '@/i18n'

export default defineI18nConfig(() => ({
  legacy: false,
  fallbackLocale: 'sl',
  // A missing message is a test's failure (locales.spec.ts), not a warning in the browser
  missingWarn: false,
  fallbackWarn: false,
  pluralRules: { sl: slovenianPlural },
}))
