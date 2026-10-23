// Episodes' and sessions' discussions as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls)

import { type Image, IMAGE_FIELDS } from '@/images'

/** Who wrote a comment: their person's name, or else their username */
export interface CommentAuthor {
  name: string
  username: string | null
  // Linked to them by verified email when they wrote it; null without one, or once they've been deleted
  person: { id: string, name: string, avatar: Image | null } | null
}

/** A comment in an episode's or a session's discussion, as on GitHub */
export interface Comment {
  id: string
  // Markdown
  body: string
  author: CommentAuthor
  createdAt: string
  // When its text last changed; null if it never did
  editedAt: string | null
  // The MCP tool it was written with, e.g. comment_on_episode; null for MetaOS's own pages
  tool: string | null
  // Whether the signed-in user wrote it, and so may edit and delete it
  mine: boolean
}

/** The most a comment may say, in characters, as MetaOS checks it (Comments.kt) */
export const MAX_COMMENT_LENGTH = 20_000

// A comment's own fields, as the discussion has them and as the mutations answer with them
const FIELDS = `id body createdAt editedAt tool mine author { name username person { id name avatar { ${IMAGE_FIELDS} } } }`

/** An episode's or a session's discussion, for a query */
export const COMMENT_FIELDS = `comments { ${FIELDS} }`

/** Where a comment goes: on an episode, by its ID, which stays while its code changes, or on a recording session */
export interface CommentPlace {
  episodeId?: string
  sessionId?: string
}

/** The mutation that comments on [place], answering with the comment as `comment` */
export function commentOn(place: CommentPlace, body: string): { query: string, variables: Record<string, string> } {
  if (place.episodeId) {
    return {
      query: `mutation ($ref: String!, $body: String!) { comment: commentOnEpisode(ref: $ref, body: $body) { ${FIELDS} } }`,
      variables: { ref: place.episodeId, body },
    }
  }
  if (place.sessionId) {
    return {
      query: `mutation ($id: ID!, $body: String!) { comment: commentOnSession(id: $id, body: $body) { ${FIELDS} } }`,
      variables: { id: place.sessionId, body },
    }
  }
  throw new Error('A comment is on an episode or a recording session')
}

/** Changes one of your comments' text, answering with the comment as `comment` */
export const EDIT_COMMENT = `mutation ($id: ID!, $body: String!) { comment: editComment(id: $id, body: $body) { ${FIELDS} } }`

/** Deletes one of your comments */
export const DELETE_COMMENT = 'mutation ($id: ID!) { deleteComment(id: $id) }'

/** A comment's text as MetaOS keeps it (Comments.kt): without blank lines before it, or space after it */
export function commentText(body: string) {
  return body.replace(/^(?:[ \t]*\r?\n)+/, '').trimEnd()
}

/** Whether MetaOS takes [body] as a comment: some text, and no more than a comment may say */
export function postable(body: string) {
  const text = commentText(body)
  return text !== '' && text.length <= MAX_COMMENT_LENGTH
}

// How many characters before the most a comment may say its box starts counting
const COUNT_FROM = 2_000

/**
 * What a comment box says of its length once it's near the most a comment may say: how many characters are left, or
 * how many too many. Counted as Comments.kt counts them, in UTF-16 units, so an emoji may be two.
 */
export function lengthNote(body: string): { text: string, over: boolean } | null {
  const left = MAX_COMMENT_LENGTH - commentText(body).length
  if (left > COUNT_FROM) return null
  const count = Math.abs(left)
  const characters = `${count.toLocaleString()} ${count === 1 ? 'character' : 'characters'}`
  return left < 0 ? { text: `${characters} too many`, over: true } : { text: `${characters} left`, over: false }
}

/** What a key does in a comment box, as on GitHub */
export type EditorShortcut = 'submit' | 'switch'

/** ⌘↵ or Ctrl+↵ sends the comment, from Write or Preview; Ctrl+Shift+P (⌘⇧P) switches between them */
export function editorShortcut(
  event: Pick<KeyboardEvent, 'key' | 'metaKey' | 'ctrlKey' | 'shiftKey' | 'altKey' | 'isComposing'>,
): EditorShortcut | null {
  // While an input method composes, its keys are its own
  if (event.isComposing || event.altKey || !(event.metaKey || event.ctrlKey)) return null
  if (event.key === 'Enter') return 'submit'
  if (event.shiftKey && event.key.toLowerCase() === 'p') return 'switch'
  return null
}

/** A comment's place on its page, for a link to it: its page's address with #comment-<id> */
export function commentAnchor(comment: Pick<Comment, 'id'>) {
  return `comment-${comment.id}`
}

/** The comment among [comments] that a page's address leads to, by its hash, e.g. #comment-<id> */
export function linkedComment<T extends Pick<Comment, 'id'>>(hash: string, comments: T[]): T | undefined {
  return comments.find((comment) => hash === `#${commentAnchor(comment)}`)
}

/** How a comment was written when it wasn't on MetaOS's pages, as the history says it (HistoryTimeline.vue) */
export function toolLabel(comment: Pick<Comment, 'tool'>) {
  return comment.tool ? `MCP · ${comment.tool}` : null
}

/** When, in full, for a title, e.g. Thursday, 22 October 2026 at 18:40 */
export function fullDateTime(at: string) {
  return new Date(at).toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'short' })
}

/**
 * What a page wrote to its discussion, as MetaOS answered, which it shows until its own data has it: the page loads
 * that again only after each write.
 */
export interface Written {
  added: Comment[]
  edited: Comment[]
  // By ID
  deleted: string[]
}

export function nothingWritten(): Written {
  return { added: [], edited: [], deleted: [] }
}

/** A discussion with what was [written] to it: new comments at the end, edited ones in their place, deleted ones gone */
export function withWritten(comments: Comment[], written: Written): Comment[] {
  if (!written.added.length && !written.edited.length && !written.deleted.length) return comments
  const there = new Set(comments.map((comment) => comment.id))
  const edited = new Map(written.edited.map((comment) => [comment.id, comment]))
  const deleted = new Set(written.deleted)
  return [...comments, ...written.added.filter((comment) => !there.has(comment.id))]
    .filter((comment) => !deleted.has(comment.id))
    .map((comment) => edited.get(comment.id) ?? comment)
}
