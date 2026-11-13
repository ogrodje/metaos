import { createI18n } from 'vue-i18n'
import enMain from '@/locales/en-GB.json'
import slMain from '@/locales/sl.json'
import { slovenianPlural } from '@/i18n'
import type { Translate } from '@/i18n'

export type Messages = { [key: string]: string | Messages }

// While the UI is being translated (OG-58), each part of it has its messages in locales/parts/<part>.<lang>.json, which
// are merged into sl.json and en-GB.json once done; until then the tests read them all, as one
const parts = import.meta.glob<Messages>('../locales/parts/*.json', { eager: true, import: 'default' })

function merged(main: Messages, lang: 'sl' | 'en-GB'): Messages {
  const into = (target: Messages, source: Messages) => {
    for (const [key, value] of Object.entries(source)) {
      if (typeof value === 'string') target[key] = value
      else into((target[key] ??= {}) as Messages, value)
    }
    return target
  }
  const all = structuredClone(main)
  for (const [path, messages] of Object.entries(parts)) {
    if (path.endsWith(`.${lang}.json`)) into(all, messages)
  }
  return all
}

/** Every message in a language, the parts' too */
export const sl = merged(slMain as Messages, 'sl')
export const en = merged(enMain as Messages, 'en-GB')

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
