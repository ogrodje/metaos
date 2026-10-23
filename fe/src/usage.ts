// What the API and MCP usage page (UsageView.vue) works out: which assistant's steps it shows, and which tools a filter
// leaves

import { fold, searchWords } from '@/search'
import type { ToolGroup } from '@/tools'

/** The assistants the MCP steps are for; their first two steps differ, approval and asking don't */
export type Client = 'claude-code' | 'claude' | 'codex' | 'chatgpt'

export const CLIENTS: { id: Client, name: string }[] = [
  { id: 'claude-code', name: 'Claude Code' },
  { id: 'claude', name: 'Claude' },
  { id: 'codex', name: 'Codex' },
  { id: 'chatgpt', name: 'ChatGPT' },
]

/** The assistant the address names (?client=), or else the first */
export function clientFrom(query: unknown): Client {
  const value = Array.isArray(query) ? query[0] : query
  return CLIENTS.find((client) => client.id === value)?.id ?? CLIENTS[0]!.id
}

/** The tab ↑ ← → ↓ Home and End move to from [index], of [count], round at the ends; null for any other key */
export function tabAfter(key: string, index: number, count: number): number | null {
  if (key === 'ArrowRight' || key === 'ArrowDown') return (index + 1) % count
  if (key === 'ArrowLeft' || key === 'ArrowUp') return (index - 1 + count) % count
  if (key === 'Home') return 0
  if (key === 'End') return count - 1
  return null
}

// As search finds them (markWords, which marks them): at the start of a word, or anywhere from three letters on
function hasWords(text: string, words: string[]) {
  const folded = fold(text)
  const parts = searchWords(text)
  return words.every((word) => parts.some((part) => part.startsWith(word)) || (word.length >= 3 && folded.includes(word)))
}

/**
 * The tools that have every word of [text] in their name, what they do, or their group's title and what it's about,
 * whatever the case and diacritics, in groups that still have any; all of them for no words
 */
export function filterTools(groups: ToolGroup[], text: string): ToolGroup[] {
  const words = searchWords(text)
  if (!words.length) return groups
  return groups.flatMap((group) => {
    const tools = group.tools.filter((tool) =>
      hasWords(`${tool.name} ${tool.description} ${group.title} ${group.about}`, words))
    return tools.length ? [{ ...group, tools }] : []
  })
}

/** How many tools there are in [groups] */
export function toolCount(groups: ToolGroup[]) {
  return groups.reduce((count, group) => count + group.tools.length, 0)
}

/**
 * A curl command that asks MetaOS's GraphQL API at [url], over a line each, which shells join again at the \: the
 * body single-quoted, which shells pass as it is, so a query uses variables rather than quotes; with the token in
 * $TOKEN for [token]
 */
export function curlCommand(url: string, body: { query: string, variables?: Record<string, unknown> }, token = false) {
  return [
    `curl -s ${url}`,
    `-H 'Content-Type: application/json'`,
    ...(token ? ['-H "Authorization: Bearer $TOKEN"'] : []),
    `-d '${JSON.stringify(body)}'`,
  ].join(' \\\n  ')
}

/**
 * [promise], or a failure after [ms]: the clipboard may wait for ever for a permission nobody's asked for, and the page
 * should rather show what it couldn't copy
 */
export function withTimeout<T>(promise: Promise<T>, ms = 3000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`No answer in ${ms} ms`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}
