import { describe, expect, it, vi } from 'vitest'
import type { ToolGroup } from '@/tools'
import { clientFrom, curlCommand, filterTools, tabAfter, toolCount, withTimeout } from '@/usage'

const GROUPS: ToolGroup[] = [
  {
    id: 'episodes',
    title: 'Episodes',
    about: 'from idea to release',
    tools: [
      { name: 'list_episodes', description: 'List episodes, latest first' },
      { name: 'set_episode_status', description: 'Move an episode through its lifecycle' },
    ],
  },
  {
    id: 'koofr',
    title: 'Koofr',
    about: 'the folders shared with the bot',
    tools: [
      { name: 'list_koofr_folder', description: "What's in a folder" },
      { name: 'upload_koofr_file', description: 'Upload a file, never replacing one unasked' },
    ],
  },
  {
    id: 'people',
    title: 'People',
    about: 'hosts, guests and you',
    tools: [{ name: 'set_person_bio', description: 'Set or clear a line about who they are, e.g. Čuhalev’s' }],
  },
]

const names = (text: string) => filterTools(GROUPS, text).flatMap((group) => group.tools.map((tool) => tool.name))

describe('clientFrom', () => {
  it("is the assistant the address names, or else Claude Code", () => {
    expect(clientFrom('codex')).toBe('codex')
    expect(clientFrom(['chatgpt', 'claude'])).toBe('chatgpt')
    expect(clientFrom('vim')).toBe('claude-code')
    expect(clientFrom(undefined)).toBe('claude-code')
  })
})

describe('tabAfter', () => {
  it('goes to the next and the previous tab, round at the ends', () => {
    expect(tabAfter('ArrowRight', 0, 4)).toBe(1)
    expect(tabAfter('ArrowRight', 3, 4)).toBe(0)
    expect(tabAfter('ArrowLeft', 0, 4)).toBe(3)
    expect(tabAfter('ArrowDown', 1, 4)).toBe(2)
  })

  it('goes to the first and the last with Home and End, and nowhere with other keys', () => {
    expect(tabAfter('Home', 2, 4)).toBe(0)
    expect(tabAfter('End', 0, 4)).toBe(3)
    expect(tabAfter('Enter', 1, 4)).toBeNull()
    expect(tabAfter('a', 1, 4)).toBeNull()
  })
})

describe('filterTools', () => {
  it('keeps every tool for no words', () => {
    expect(filterTools(GROUPS, '')).toBe(GROUPS)
    expect(filterTools(GROUPS, '  _ ')).toBe(GROUPS)
  })

  it("finds tools by their name's words, as typed with underscores or without", () => {
    expect(names('list')).toEqual(['list_episodes', 'list_koofr_folder'])
    expect(names('list_koofr')).toEqual(['list_koofr_folder'])
    expect(names('koofr list')).toEqual(['list_koofr_folder'])
  })

  it('finds them by what they do, and by their group, whatever the case and diacritics', () => {
    expect(names('UPLOAD')).toEqual(['upload_koofr_file'])
    expect(names('cuhalev')).toEqual(['set_person_bio'])
    expect(names('people')).toEqual(['set_person_bio'])
    expect(names('release')).toEqual(['list_episodes', 'set_episode_status'])
  })

  it('finds inside words from three letters on, as search marks them', () => {
    expect(names('fecycle')).toEqual(['set_episode_status'])
    expect(names('ec')).toEqual([])
  })

  it('leaves out groups with no tool left, and keeps the rest as they were', () => {
    const groups = filterTools(GROUPS, 'upload')
    expect(groups.map((group) => group.id)).toEqual(['koofr'])
    expect(groups[0]?.title).toBe('Koofr')
    expect(filterTools(GROUPS, 'nothing like it')).toEqual([])
  })
})

describe('toolCount', () => {
  it('counts the tools in all the groups', () => {
    expect(toolCount(GROUPS)).toBe(5)
    expect(toolCount(filterTools(GROUPS, 'list'))).toBe(2)
  })
})

describe('curlCommand', () => {
  const URL = 'https://metaos.ogrodje.si/graphql'
  // As a shell runs it: the lines joined again at their backslashes
  const oneLine = (command: string) => command.replaceAll(' \\\n  ', ' ')

  it('asks for what is public without a token', () => {
    const command = curlCommand(URL, { query: '{ releasedEpisodes(limit: 3) { code title airedDate youtubeUrl } }' })
    expect(command).toBe([
      `curl -s ${URL} \\`,
      `  -H 'Content-Type: application/json' \\`,
      `  -d '{"query":"{ releasedEpisodes(limit: 3) { code title airedDate youtubeUrl } }"}'`,
    ].join('\n'))
  })

  it('sends the token in $TOKEN, and variables, so the body has no quotes to escape', () => {
    const command = curlCommand(URL, {
      query: 'mutation ($ref: String!, $body: String!) { commentOnEpisode(ref: $ref, body: $body) { id createdAt } }',
      variables: { ref: 'S05E08', body: 'Hello from the API' },
    }, true)
    expect(oneLine(command)).toBe(`curl -s ${URL} -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" `
      + `-d '{"query":"mutation ($ref: String!, $body: String!) { commentOnEpisode(ref: $ref, body: $body) { id createdAt } }",`
      + `"variables":{"ref":"S05E08","body":"Hello from the API"}}'`)
  })
})

describe('withTimeout', () => {
  it('passes on what comes in time', async () => {
    await expect(withTimeout(Promise.resolve('copied'), 50)).resolves.toBe('copied')
    await expect(withTimeout(Promise.reject(new Error('refused')), 50)).rejects.toThrow('refused')
  })

  it("fails what doesn't come, e.g. a clipboard waiting for a permission", async () => {
    vi.useFakeTimers()
    try {
      const waiting = withTimeout(new Promise(() => {}), 3000)
      vi.advanceTimersByTime(3000)
      await expect(waiting).rejects.toThrow('No answer in 3000 ms')
    } finally {
      vi.useRealTimers()
    }
  })
})
