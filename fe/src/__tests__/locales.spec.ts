import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { slovenianPlural } from '@/i18n'
import { en, sl, t, tSl } from './i18n'

// Every message in both languages (OG-58): a key missing in one, or a key the code asks for that neither has, fails

type Messages = typeof sl

function keys(messages: Messages, prefix = ''): string[] {
  return Object.entries(messages).flatMap(([key, value]) =>
    typeof value === 'string' ? [prefix + key] : keys(value, `${prefix}${key}.`))
}

const src = join(__dirname, '..')

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (name === '__tests__' || name === 'locales' || name === 'node_modules') return []
    if (statSync(path).isDirectory()) return sources(path)
    return /\.(vue|ts)$/.test(name) ? [path] : []
  })
}

// The keys the code names outright: t('...'), $t('...'), <I18nT keypath="..."> and the routes' titles (title: '...')
function usedKeys(): Set<string> {
  const used = new Set<string>()
  for (const file of sources(src)) {
    const text = readFileSync(file, 'utf8')
    for (const match of text.matchAll(/\b\$?t\(\s*'([a-zA-Z][\w-]*(?:\.[\w-]+)+)'/g)) used.add(match[1]!)
    // A sentence with a link or markup in it: <I18nT keypath="..."> (or <i18n-t>)
    for (const match of text.matchAll(/\bkeypath="([a-zA-Z][\w-]*(?:\.[\w-]+)+)"/g)) used.add(match[1]!)
    if (file.endsWith(join('router', 'index.ts'))) {
      for (const match of text.matchAll(/title: '([\w.]+)'/g)) used.add(match[1]!)
    }
  }
  return used
}

describe('the messages', () => {
  const slKeys = new Set(keys(sl as Messages))
  const enKeys = new Set(keys(en as Messages))

  it('has every Slovenian message in English', () => {
    expect([...slKeys].filter((key) => !enKeys.has(key))).toEqual([])
  })

  it('has every English message in Slovenian', () => {
    expect([...enKeys].filter((key) => !slKeys.has(key))).toEqual([])
  })

  it('has every key the code asks for', () => {
    const missing = [...usedKeys()].filter((key) => !slKeys.has(key) || !enKeys.has(key))
    expect(missing).toEqual([])
  })

  it('has no empty message', () => {
    const empty = (messages: Messages) => keys(messages).filter((key) =>
      key.split('.').reduce<string | Messages>((at, part) => (at as Messages)[part]!, messages) === '')
    expect([...empty(sl as Messages), ...empty(en as Messages)]).toEqual([])
  })
})

describe("Slovenian's plurals", () => {
  it('has four forms: one, two, few and other', () => {
    expect([1, 2, 3, 4, 5, 11, 100, 101, 102, 103, 0].map((n) => slovenianPlural(n, 4))).toEqual([0, 1, 2, 2, 3, 3, 3, 0, 1, 2, 3])
  })

  it('says a count as Slovenian does', () => {
    const photos = (n: number) => tSl('episodes.pictures.photos', { n }, n)
    expect([1, 2, 3, 5, 101].map(photos)).toEqual(['1 fotografija', '2 fotografiji', '3 fotografije', '5 fotografij', '101 fotografija'])
    expect(t('episodes.pictures.photos', { n: 1 }, 1)).toBe('1 photo')
    expect(t('episodes.pictures.photos', { n: 2 }, 2)).toBe('2 photos')
  })
})
