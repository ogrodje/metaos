import { describe, expect, it } from 'vitest'
import { activity, BURST_MINUTES, type ChangeEntry, filtered, isNew, recentActivity, shortWhen } from '@/activity'
import type { Comment } from '@/comments'
import type { Change, ChangeKind, ChangeSubject, FieldChange } from '@/episodes'

function change(at: string, summary: string, kind: ChangeKind = 'EPISODE', fields: FieldChange[] = [], more: Partial<Change> = {}): Change {
  return { at: `2026-10-19T${at}:00Z`, actor: 'Oto Brglez', client: 'claude', tool: 'update_episode', kind, commentId: null, summary, fields, ...more }
}

const field = (name: string, from: string | null, to: string | null): FieldChange => ({ field: name, from, to })

function comment(id: string, at: string): Comment {
  return {
    id, body: 'Hi', createdAt: `2026-10-19T${at}:00Z`, editedAt: null, tool: null, mine: true,
    author: { name: 'Oto Brglez', username: 'oto', person: null },
  }
}

const changes = (entries: ReturnType<typeof activity>) => entries.filter((entry): entry is ChangeEntry => entry.type === 'changes')

// S05E08's first minutes, as production's history has them
const created = [
  change('16:34', 'Created the episode', 'EPISODE', [field('Code', null, 'S05E08'), field('Title', null, 'Poletna vročina'), field('Status', null, 'idea')], { tool: 'create_episode' }),
  change('16:34', 'Added Andraž Sraka as host', 'PERSON', [], { tool: 'assign_person_to_episode' }),
  change('16:35', 'Added Andraž Brodnik as guest', 'PERSON', [], { tool: 'assign_person_to_episode' }),
  change('16:35', 'Changed the episode', 'EPISODE', [field('Title', 'Poletna vročina', 'Poletna vročica')]),
  change('16:40', 'Changed the episode', 'EPISODE', [field('Status', 'idea', 'planned')], { tool: 'set_episode_status' }),
  change('16:40', 'Changed the episode', 'EPISODE', [field('Status', 'planned', 'scheduled')], { tool: 'set_episode_status' }),
  change('16:41', 'Changed the episode', 'EPISODE', [field('Status', 'scheduled', 'recorded')], { tool: 'set_episode_status' }),
  change('16:41', 'Changed the episode', 'EPISODE', [field('Status', 'recorded', 'in production')], { tool: 'set_episode_status' }),
]

describe('activity', () => {
  it("reads one person's changes made close together as one entry", () => {
    const [entry] = changes(activity(created, []))
    expect(activity(created, [])).toHaveLength(1)
    expect(entry?.headline).toBe('Created the episode')
    expect(entry?.count).toBe(8)
    expect(entry?.how).toBe('MCP · create_episode, assign_person_to_episode, update_episode, set_episode_status')
    expect(entry?.lines).toEqual([
      { label: 'Code', text: 'S05E08' },
      { label: 'Title', text: 'Poletna vročica' },
      { label: 'Status', text: 'idea → planned → scheduled → recorded → in production' },
      { label: null, text: 'Added Andraž Sraka as host' },
      { label: null, text: 'Added Andraž Brodnik as guest' },
    ])
  })

  it(`starts another entry after ${BURST_MINUTES} minutes, or for someone else`, () => {
    const entries = activity([
      change('10:00', 'Linked a Google Doc', 'DOCUMENT'),
      change('10:16', 'Linked the Koofr folder /Epizode/S05E08', 'KOOFR_FOLDER'),
      change('10:17', 'Added Jan Dolar as producer', 'PERSON', [], { actor: 'Andraž Sraka' }),
    ], [])
    expect(changes(entries).map((entry) => entry.headline))
      .toEqual(['Linked a Google Doc', 'Linked the Koofr folder /Epizode/S05E08', 'Added Jan Dolar as producer'])
  })

  it('tells a field from what it was first to what it became last, and leaves out one changed back', () => {
    const [entry] = changes(activity([
      change('10:00', 'Changed the episode', 'EPISODE', [field('Title', 'A', 'B'), field('Title B', null, 'X')]),
      change('10:05', 'Changed the episode', 'EPISODE', [field('Title', 'B', 'C'), field('Summary', null, null)]),
      change('10:06', 'Changed the episode', 'EPISODE', [field('Title B', 'X', null)]),
    ], []))
    expect(entry?.headline).toBe('Changed the episode')
    expect(entry?.lines).toEqual([
      { label: 'Title', text: 'A → C' },
      { label: 'Title B', text: 'changed' },
      { label: 'Summary', text: 'changed' },
    ])
  })

  it('says what a burst of different changes did in one sentence', () => {
    const [entry] = changes(activity([
      change('10:00', 'Changed the episode', 'EPISODE', [field('Recording date', null, '9 Jul 2026')]),
      change('10:01', 'Added Ana as guest', 'PERSON'),
      change('10:01', 'Added Bor as guest', 'PERSON'),
      change('10:02', 'Linked a Google Doc', 'DOCUMENT'),
    ], []))
    expect(entry?.headline).toBe('Changed the episode, added 2 people and linked a Google Doc')
    expect(entry?.lines.map((line) => line.text)).toEqual(['9 Jul 2026', 'Added Ana as guest', 'Added Bor as guest', 'Linked a Google Doc'])
  })

  it('says a change made more than once once, with how often', () => {
    const [entry] = changes(activity([
      change('10:00', 'Commented', 'COMMENT', [], { commentId: 'gone-1' }),
      change('10:01', 'Commented', 'COMMENT', [], { commentId: 'gone-2' }),
      change('10:02', "Changed Ana's role", 'PERSON'),
    ], []))
    expect(entry?.headline).toBe('Changed roles and commented twice')
  })

  it('links a folder linked, unlinked and linked again once, without the link Dropbox made to it', () => {
    const [entry] = changes(activity([
      change('10:10', 'Linked the Dropbox folder /Season 5/S05E07', 'DROPBOX_FOLDER'),
      change('10:11', 'Unlinked the Dropbox folder /Season 5/S05E07', 'DROPBOX_FOLDER'),
      change('10:12', 'Linked the Dropbox folder /Season 5/S05E07', 'DROPBOX_FOLDER'),
      change('10:12', 'Made a link to the Dropbox folder /Season 5/S05E07', 'DROPBOX_FOLDER'),
    ], []))
    expect(entry?.headline).toBe('Linked the Dropbox folder /Season 5/S05E07')
    expect(entry?.lines).toEqual([])
    expect(entry?.count).toBe(4)
  })

  it("leaves out writing and editing the comments it shows, but not deleting one that's gone", () => {
    const entries = activity([
      change('10:00', 'Commented', 'COMMENT', [field('Comment', null, 'Hi')], { commentId: 'c1', tool: null, client: 'metaos-ui' }),
      change('10:01', 'Edited a comment', 'COMMENT', [], { commentId: 'c1', tool: null, client: 'metaos-ui' }),
      change('11:00', 'Deleted a comment', 'COMMENT', [], { commentId: 'c2', tool: null, client: 'metaos-ui' }),
    ], [comment('c1', '10:00')])
    expect(entries.map((entry) => (entry.type === 'comment' ? `comment ${entry.comment.id}` : entry.headline)))
      .toEqual(['comment c1', 'Deleted a comment'])
    expect(changes(entries)[0]?.how).toBe('MetaOS')
  })

  it('puts comments and changes in the order they were made, and filters them', () => {
    const entries = activity([change('09:00', 'Linked a Google Doc', 'DOCUMENT'), change('12:00', 'Added Ana as guest', 'PERSON')],
      [comment('c1', '10:00')])
    expect(entries.map((entry) => entry.type)).toEqual(['changes', 'comment', 'changes'])
    expect(filtered(entries, 'comments')).toHaveLength(1)
    expect(filtered(entries, 'changes')).toHaveLength(2)
    expect(filtered(entries, 'all')).toHaveLength(3)
  })

  it('keeps changes made outside MetaOS apart from those of someone signed in', () => {
    const entries = changes(activity([
      change('10:00', 'Changed the episode', 'EPISODE', [field('Aired', null, '13 Oct 2026')]),
      change('10:01', 'Changed the episode', 'EPISODE', [field('Status', 'ready', 'published')], { actor: null, client: null, tool: null }),
    ], []))
    expect(entries.map((entry) => [entry.actor, entry.how])).toEqual([['Oto Brglez', 'MCP · update_episode'], [null, null]])
  })
})

describe('recentActivity', () => {
  const idea = (id: string): ChangeSubject => ({ kind: 'EPISODE', id, name: `Idea ${id}`, code: null })

  it("counts one person's changes to several things by what they're to, the newest entry first", () => {
    const entries = recentActivity([
      change('10:00', 'Created the episode', 'EPISODE', [], { about: idea('1'), actor: 'Andraž Sraka' }),
      change('10:01', 'Added Žan Gojčič as candidate', 'PERSON', [], { about: idea('1'), actor: 'Andraž Sraka' }),
      change('10:02', 'Created the episode', 'EPISODE', [], { about: idea('2'), actor: 'Andraž Sraka' }),
      change('10:03', 'Created the episode', 'EPISODE', [], { about: idea('3'), actor: 'Andraž Sraka' }),
      change('10:04', 'Created the episode', 'EPISODE', [], { about: idea('4'), actor: 'Andraž Sraka' }),
      change('11:00', 'Linked a Google Doc', 'DOCUMENT', [], { about: idea('1') }),
    ])
    expect(entries.map((entry) => entry.headline)).toEqual(['Linked a Google Doc', 'Created 4 episodes'])
    expect(entries[1]?.about.map((subject) => subject.id)).toEqual(['1', '2', '3'])
    expect(entries[1]?.more).toBe(1)
    expect(entries[1]?.count).toBe(5)
  })

  it('says it changed things when not all of them were created', () => {
    const [entry] = recentActivity([
      change('10:00', 'Changed the episode', 'EPISODE', [field('Title', 'A', 'B')], { about: idea('1') }),
      change('10:01', 'Changed the person', 'PERSON', [field('Bio', null, 'X')], { about: { kind: 'PERSON', id: 'p', name: 'Ana', code: null } }),
    ])
    expect(entry?.headline).toBe('Changed 1 episode and 1 person')
  })
})

describe("what's new", () => {
  const seen = Date.parse('2026-10-19T16:00:00Z')
  const by = (actor: string, ...at: string[]) => at.map((time) =>
    change(time, 'Changed the episode', 'EPISODE', [field('Title', 'A', 'B')], { actor }))

  it('is what others did since the page was last opened', () => {
    const [entry] = activity(by('Jan Dolar', '16:30'), [])
    expect(isNew(entry!, seen, 'Oto Brglez')).toBe(true)
    expect(isNew(entry!, seen, 'Jan Dolar')).toBe(false)
    expect(isNew(entry!, Date.parse('2026-10-19T17:00:00Z'), 'Oto Brglez')).toBe(false)
  })

  it('is nothing the first time the page is opened', () => {
    const [entry] = activity(by('Jan Dolar', '16:30'), [])
    expect(isNew(entry!, null, 'Oto Brglez')).toBe(false)
  })

  it("isn't one's own comment", () => {
    const [mine] = activity([], [comment('1', '16:30')])
    const [theirs] = activity([], [{ ...comment('2', '16:30'), mine: false }])
    expect(isNew(mine!, seen, null)).toBe(false)
    expect(isNew(theirs!, seen, null)).toBe(true)
  })

  it('counts changes made close together by when the last was made', () => {
    const [entry] = activity(by('Jan Dolar', '15:50', '16:05'), [])
    expect(isNew(entry!, seen, 'Oto Brglez')).toBe(true)
  })
})

describe('shortWhen', () => {
  // 15:00 in Ljubljana
  const now = new Date('2026-10-28T14:00:00Z')

  it('says today and yesterday with the time, else the date, with its year when it was another', () => {
    expect(shortWhen('2026-10-28T13:14:00Z', now)).toBe('today, 14:14')
    expect(shortWhen('2026-10-27T16:32:00Z', now)).toBe('yesterday, 17:32')
    expect(shortWhen('2026-10-23T10:00:00Z', now)).toBe('23 Oct')
    expect(shortWhen('2025-10-23T10:00:00Z', now)).toBe('23 Oct 2025')
  })

  it("goes by Ogrodje's days, whatever the browser's time zone", () => {
    expect(shortWhen('2026-10-27T23:30:00Z', now)).toBe('today, 00:30')
  })
})

