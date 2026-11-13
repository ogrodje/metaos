import { computed } from 'vue'
import { useI18n, useLocalePath } from '#imports'
import { dateLocaleOf } from '@/i18n'

/**
 * The page's language, in a component (OG-58): `t` for its messages (src/locales), `dateLocale` for dates and numbers
 * (sl-SI or en-GB), and `path` for a link in the same language, e.g. path('/episodes') is /episodes in Slovenian and
 * /en/episodes in English. A module that makes text or dates (episodes.ts, ...) takes `t` and `dateLocale` as
 * arguments, as it can't ask for them itself.
 */
export function useLocale() {
  const { t, locale } = useI18n()
  const localePath = useLocalePath()
  const dateLocale = computed(() => dateLocaleOf(locale.value))
  const path = (to: string) => localePath(to)
  return { t, locale, dateLocale, path }
}
