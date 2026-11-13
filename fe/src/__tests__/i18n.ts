import { createI18n } from 'vue-i18n'
import enMessages from '@/locales/en-GB.json'
import slMessages from '@/locales/sl.json'
import { slovenianPlural } from '@/i18n'
import type { Translate } from '@/i18n'

export type Messages = { [key: string]: string | Messages }

/** Every message in a language (locales/*.json) */
export const sl = slMessages as Messages
export const en = enMessages as Messages

// The UI's messages for the unit tests, which run without Nuxt: a translator in each language, as useLocale() gives a
// component, so a module's tests read what it says in English (`t`), or in Slovenian (`tSl`)

function translator(locale: 'sl' | 'en'): Translate {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'sl',
    missingWarn: false,
    fallbackWarn: false,
    messages: { sl, en },
    pluralRules: { sl: slovenianPlural },
  }).global.t as Translate
}

export const t = translator('en')
export const tSl = translator('sl')
