import type { Composer } from 'vue-i18n'

// The UI's languages, as plain types and functions, for modules the unit tests load without Nuxt (src/**/__tests__):
// a component gets them from useLocale() (useLocale.ts), and passes them on to what it calls

/** Translates a message key, e.g. t('episodes.status.IDEA'): vue-i18n's, as useI18n() gives it (or a test's) */
export type Translate = Composer['t']

/** The locale dates and numbers are written in: Slovenia's or the UK's, as the UI is (nuxt.config.ts's i18n) */
export type DateLocale = 'sl-SI' | 'en-GB'

/** Ogrodje's time zone, which dates are in wherever the browser is: the server renders them so, and the browser's match */
export const TIME_ZONE = 'Europe/Ljubljana'

/** The date locale of a UI locale: `en` English (UK), anything else Slovenian, the default */
export function dateLocaleOf(locale: string): DateLocale {
  return locale === 'en' ? 'en-GB' : 'sl-SI'
}

/**
 * Slovenian's plural forms, which vue-i18n doesn't know: one (1, 101), two (2, 102), few (3, 4, 103, 104) and other
 * (0, 5–100, ...), as CLDR has them. A Slovenian message with a count has four forms, in that order:
 * `"{n} epizoda | {n} epizodi | {n} epizode | {n} epizod"`; English two, `"{n} episode | {n} episodes"`.
 */
export function slovenianPlural(choice: number, choicesLength: number): number {
  const n = Math.abs(choice) % 100
  const form = n === 1 ? 0 : n === 2 ? 1 : n === 3 || n === 4 ? 2 : 3
  return Math.min(form, choicesLength - 1)
}
