// An episode's or a recording session's activity: its discussion and its history in one feed, oldest first, as on
// GitHub. One person's changes made close together read as one entry, e.g. creating an episode and adding its people.

import type { Comment } from '@/comments'
import type { Change, FieldChange } from '@/episodes'

/** How far apart one person's changes may be and still read as one entry */
export const BURST_MINUTES = 15

/** A line of an entry: a field and what it became, or a change told in words (without a label) */
export interface ActivityLine {
  label: string | null
  text: string
}

/** Changes one person made close together */
export interface ChangeEntry {
  type: 'changes'
  key: string
  // When the first and the last of them were made
  at: string
  until: string
  // Who made them; null for changes made outside MetaOS, e.g. by a migration
  actor: string | null
  // How, e.g. MetaOS, or MCP · create_episode, assign_person_to_episode; null outside MetaOS
  how: string | null
  headline: string
  lines: ActivityLine[]
  // How many changes of the history it stands for
  count: number
}

export interface CommentEntry {
  type: 'comment'
  key: string
  at: string
  comment: Comment
}

export type ActivityEntry = ChangeEntry | CommentEntry

/** What a feed shows: everything, only the comments, or only the changes */
export type ActivityFilter = 'all' | 'comments' | 'changes'

/**
 * The [comments] and the [changes] (both oldest first, as the API has them) as one feed, oldest first. Writing or
 * editing a comment that's shown is in its card already, so those changes are left out; deleting one isn't.
 */
export function activity(changes: Change[], comments: Comment[]): ActivityEntry[] {
  const shown = new Set(comments.map((comment) => comment.id))
  const told = changes.filter((change) => !(change.kind === 'COMMENT' && change.commentId && shown.has(change.commentId)))
  const entries: ActivityEntry[] = [
    ...bursts(told).map(entry),
    ...comments.map((comment): CommentEntry => ({ type: 'comment', key: `comment-${comment.id}`, at: comment.createdAt, comment })),
  ]
  // Stable, so a change and a comment at the same moment keep that order
  return entries.sort((a, b) => Date.parse(a.at) - Date.parse(b.at))
}

/** The entries a filter shows */
export function filtered(entries: ActivityEntry[], filter: ActivityFilter) {
  if (filter === 'comments') return entries.filter((entry) => entry.type === 'comment')
  if (filter === 'changes') return entries.filter((entry) => entry.type === 'changes')
  return entries
}

// Consecutive changes by the same person, each within BURST_MINUTES of the one before
function bursts(changes: Change[]): Change[][] {
  const found: Change[][] = []
  for (const change of changes) {
    const burst = found.at(-1)
    const last = burst?.at(-1)
    if (burst && last && last.actor === change.actor && outside(last) === outside(change)
      && Date.parse(change.at) - Date.parse(last.at) <= BURST_MINUTES * 60_000) burst.push(change)
    else found.push([change])
  }
  return found
}

// Made without signing in to MetaOS, e.g. with psql or by a migration
function outside(change: Change) {
  return !change.actor && !change.client
}

function entry(changes: Change[]): ChangeEntry {
  const first = changes[0]!
  const last = changes.at(-1)!
  const { headline, lines } = told(changes)
  return {
    type: 'changes', key: `changes-${first.at}`, at: first.at, until: last.at, actor: first.actor, how: how(changes),
    headline, lines, count: changes.length,
  }
}

// How they were made: on MetaOS's pages, through MCP with its tools, or through an MCP client without a tool's name
function how(changes: Change[]): string | null {
  const tools = [...new Set(changes.flatMap((change) => (change.tool ? [change.tool] : [])))]
  const parts = [
    ...(changes.some((change) => !change.tool && change.client === 'metaos-ui') ? ['MetaOS'] : []),
    ...(tools.length ? [`MCP · ${tools.join(', ')}`]
      : changes.some((change) => change.client && change.client !== 'metaos-ui') ? ['MCP'] : []),
  ]
  return parts.length ? parts.join(', ') : null
}

// The episode's or session's own fields, as changes to them have them
const OWN = new Set(['EPISODE', 'SESSION'])

// What the changes did: a headline, and a line for each field (what it was first and became last) and each other change
function told(changes: Change[]): { headline: string, lines: ActivityLine[] } {
  const fields = mergedFields(changes)
  const others = otherSummaries(changes)
  const lines: ActivityLine[] = [...fields, ...others.map((text) => ({ label: null, text }))]
  const created = changes.find((change) => change.summary.startsWith('Created the'))
  if (created) return { headline: created.summary, lines }
  if (changes.length === 1 || (others.length === 1 && !fields.length)) {
    const headline = others.length === 1 && !fields.length ? others[0]! : changes[0]!.summary
    return { headline, lines: lines.filter((line) => line.text !== headline) }
  }
  return { headline: headlineOf(changes, others), lines }
}

// Each field once, in the order it first changed: from what it was before the first change to what the last made it.
// A status shows each step, as an episode moves through them.
function mergedFields(changes: Change[]): ActivityLine[] {
  const merged = new Map<string, { from: string | null, to: string | null, steps: (string | null)[] }>()
  for (const change of changes.filter((change) => OWN.has(change.kind))) {
    for (const field of change.fields) {
      const seen = merged.get(field.field)
      if (seen) {
        seen.to = field.to
        seen.steps.push(field.to)
      } else {
        merged.set(field.field, { from: field.from, to: field.to, steps: [field.from, field.to] })
      }
    }
  }
  return [...merged].flatMap(([label, { from, to, steps }]) => {
    if (from !== null && from === to) return []
    const path = steps.filter((step, i) => step !== null && step !== steps[i - 1])
    const text = label === 'Status' && path.length > 2 ? path.join(' → ') : value({ field: label, from, to })
    return [{ label, text }]
  })
}

function value(field: FieldChange) {
  if (field.from === null && field.to === null) return 'changed'
  if (field.from === null) return field.to!
  if (field.to === null) return `removed (was ${field.from})`
  return `${field.from} → ${field.to}`
}

// The changes told in words, but for those to the episode's or session's own fields, which are lines of their own. A
// folder linked, unlinked and linked again is linked once, and the link Dropbox makes to a folder just linked isn't
// worth a line.
function otherSummaries(changes: Change[]): string[] {
  const told = new Map<string, string>()
  changes.forEach((change, i) => {
    if (OWN.has(change.kind) && change.fields.length) return
    if (change.summary.startsWith('Changed the') && !change.fields.length) return
    if (change.kind === 'KOOFR_FOLDER' || change.kind === 'DROPBOX_FOLDER') {
      const folder = `${change.kind}:${change.summary.slice(change.summary.indexOf(' folder ') + 8)}`
      const before = told.get(folder)
      if (change.summary.startsWith('Unlinked') && before?.startsWith('Linked')) told.delete(folder)
      else if (!(change.summary.startsWith('Made a link') && before?.startsWith('Linked'))) told.set(folder, change.summary)
      return
    }
    told.set(`${i}`, change.summary)
  })
  return [...told.values()]
}

// What a burst of changes did, as one sentence: changed the episode, added 4 people and linked the Koofr folder
function headlineOf(changes: Change[], others: string[]): string {
  const parts: string[] = []
  const own = changes.find((change) => OWN.has(change.kind) && change.fields.length)
  if (own) parts.push(own.summary)
  const people = changes.filter((change) => change.kind === 'PERSON')
  const added = people.filter((change) => change.summary.startsWith('Added '))
  const removed = people.filter((change) => change.summary.startsWith('Removed '))
  if (added.length) parts.push(added.length === 1 ? added[0]!.summary : `added ${added.length} people`)
  if (removed.length) parts.push(removed.length === 1 ? removed[0]!.summary : `removed ${removed.length} people`)
  if (people.length > added.length + removed.length) parts.push('changed roles')
  const counted = new Set(people.map((change) => change.summary))
  parts.push(...others.filter((summary) => !counted.has(summary)))
  const said = parts.length > 3 ? [...parts.slice(0, 2), `${parts.length - 2} more changes`] : parts
  const sentence = said.map((part, i) => (i ? part.charAt(0).toLowerCase() + part.slice(1) : part))
  return sentence.length > 1 ? `${sentence.slice(0, -1).join(', ')} and ${sentence.at(-1)}` : sentence[0] ?? `${changes.length} changes`
}
