// The language a visitor chose (LanguageSwitch.vue), kept in this browser for the pages opened next
// (plugins/locale.client.ts, OG-58); in localStorage, which may be off, e.g. in a private window

const KEY = 'metaos.locale'

/** Remembers the language chosen, for the pages opened next */
export function rememberLocale(code: string) {
  try {
    localStorage.setItem(KEY, code)
  } catch {
    // Storage may be off, e.g. in a private window: the choice holds for this page only
  }
}

export function chosenLocale(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}
