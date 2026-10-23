import { describe, expect, it } from 'vitest'
import {
  type Comment, commentAnchor, COMMENT_FIELDS, commentOn, commentText, editorShortcut, lengthNote, linkedComment,
  MAX_COMMENT_LENGTH, nothingWritten, postable, toolLabel, withWritten,
} from '@/comments'

function comment(id: string, body = `Comment ${id}`): Comment {
  return {
    id,
    body,
    author: { name: 'Maja Kovač', username: 'maja', person: null },
    createdAt: '2026-10-19T21:30:12.345+02:00',
    editedAt: null,
    tool: null,
    mine: true,
  }
}

// A key as a browser has it, nothing held but what's given
function key(key: string, held: Partial<Pick<KeyboardEvent, 'metaKey' | 'ctrlKey' | 'shiftKey' | 'altKey' | 'isComposing'>> = {}) {
  return { key, metaKey: false, ctrlKey: false, shiftKey: false, altKey: false, isComposing: false, ...held }
}

describe('editorShortcut', () => {
  it('sends with ⌘↵ or Ctrl+↵', () => {
    expect(editorShortcut(key('Enter', { metaKey: true }))).toBe('submit')
    expect(editorShortcut(key('Enter', { ctrlKey: true }))).toBe('submit')
  })

  it('leaves ↵ alone, and Shift+↵, which are new lines', () => {
    expect(editorShortcut(key('Enter'))).toBeNull()
    expect(editorShortcut(key('Enter', { shiftKey: true }))).toBeNull()
  })

  it('switches Write and Preview with Ctrl+Shift+P or ⌘⇧P', () => {
    expect(editorShortcut(key('P', { ctrlKey: true, shiftKey: true }))).toBe('switch')
    expect(editorShortcut(key('p', { metaKey: true, shiftKey: true }))).toBe('switch')
  })

  it("leaves Ctrl+P and ⌘P to the browser, which prints", () => {
    expect(editorShortcut(key('p', { ctrlKey: true }))).toBeNull()
    expect(editorShortcut(key('p', { metaKey: true }))).toBeNull()
  })

  it('leaves Alt and what an input method is composing alone', () => {
    expect(editorShortcut(key('Enter', { metaKey: true, altKey: true }))).toBeNull()
    expect(editorShortcut(key('Enter', { ctrlKey: true, isComposing: true }))).toBeNull()
  })
})

describe('commentText', () => {
  it('drops blank lines before the text and space after it, as MetaOS does', () => {
    expect(commentText('\n  \n\tHello\n\n  ')).toBe('\tHello')
  })

  it("keeps the first line's indent, which may start a code block", () => {
    expect(commentText('    const x = 1\n')).toBe('    const x = 1')
  })
})

describe('postable', () => {
  it('takes some text', () => {
    expect(postable('Hello')).toBe(true)
  })

  it("doesn't take nothing, or only space", () => {
    expect(postable('')).toBe(false)
    expect(postable(' \n\t\n ')).toBe(false)
  })

  it('takes as much as a comment may say, and no more', () => {
    expect(postable('x'.repeat(MAX_COMMENT_LENGTH))).toBe(true)
    expect(postable('x'.repeat(MAX_COMMENT_LENGTH + 1))).toBe(false)
  })

  it("doesn't count the space MetaOS drops", () => {
    expect(postable(`\n\n${'x'.repeat(MAX_COMMENT_LENGTH)}\n\n`)).toBe(true)
  })
})

describe('lengthNote', () => {
  it('says nothing while the text is short of the most', () => {
    expect(lengthNote('Hello')).toBeNull()
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH - 2001))).toBeNull()
  })

  it('counts what is left once it is near', () => {
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH - 2000))).toEqual({ text: `${(2000).toLocaleString()} characters left`, over: false })
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH - 1))).toEqual({ text: '1 character left', over: false })
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH))).toEqual({ text: '0 characters left', over: false })
  })

  it('counts how many too many past it', () => {
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH + 1))).toEqual({ text: '1 character too many', over: true })
    expect(lengthNote('x'.repeat(MAX_COMMENT_LENGTH + 12))).toEqual({ text: '12 characters too many', over: true })
  })

  it('counts as MetaOS does, in UTF-16 units', () => {
    expect(lengthNote('🎙'.repeat(MAX_COMMENT_LENGTH / 2 + 1))).toEqual({ text: '2 characters too many', over: true })
  })
})

describe('linkedComment', () => {
  const comments = [comment('a1'), comment('b2')]

  it("is the comment the address's hash leads to", () => {
    expect(commentAnchor(comments[1]!)).toBe('comment-b2')
    expect(linkedComment('#comment-b2', comments)).toBe(comments[1])
  })

  it('is none for another hash, none, or a comment not there', () => {
    expect(linkedComment('', comments)).toBeUndefined()
    expect(linkedComment('#history', comments)).toBeUndefined()
    expect(linkedComment('#comment-c3', comments)).toBeUndefined()
    expect(linkedComment('#comment-b', comments)).toBeUndefined()
  })
})

describe('toolLabel', () => {
  it('names the MCP tool, as the history does', () => {
    expect(toolLabel({ tool: 'comment_on_episode' })).toBe('MCP · comment_on_episode')
  })

  it('says nothing for comments from MetaOS’s pages', () => {
    expect(toolLabel({ tool: null })).toBeNull()
  })
})

describe('commentOn', () => {
  it('comments on an episode by its ID, as its ref', () => {
    const { query, variables } = commentOn({ episodeId: 'e1' }, 'Hello')
    expect(query).toContain('comment: commentOnEpisode(ref: $ref, body: $body)')
    expect(variables).toEqual({ ref: 'e1', body: 'Hello' })
  })

  it('comments on a session by its ID', () => {
    const { query, variables } = commentOn({ sessionId: 's1' }, 'Hello')
    expect(query).toContain('comment: commentOnSession(id: $id, body: $body)')
    expect(variables).toEqual({ id: 's1', body: 'Hello' })
  })

  it('answers with the fields the discussion has', () => {
    const fields = COMMENT_FIELDS.replace(/^comments \{ (.*) }$/, '$1')
    expect(commentOn({ episodeId: 'e1' }, 'Hello').query).toContain(`{ ${fields} }`)
  })

  it('needs one or the other', () => {
    expect(() => commentOn({}, 'Hello')).toThrow()
  })
})

describe('withWritten', () => {
  const comments = [comment('1'), comment('2')]

  it('is the discussion itself while nothing was written', () => {
    expect(withWritten(comments, nothingWritten())).toBe(comments)
  })

  it('adds new comments at the end, once', () => {
    const added = comment('3')
    expect(withWritten(comments, { ...nothingWritten(), added: [added] }).map((c) => c.id)).toEqual(['1', '2', '3'])
    // The page's data already has it
    expect(withWritten([...comments, added], { ...nothingWritten(), added: [added] }).map((c) => c.id)).toEqual(['1', '2', '3'])
  })

  it('shows edited comments in their place', () => {
    const edited = { ...comment('1', 'Changed'), editedAt: '2026-10-20T08:00:00.000+02:00' }
    expect(withWritten(comments, { ...nothingWritten(), edited: [edited] })).toEqual([edited, comments[1]])
  })

  it('leaves deleted comments out', () => {
    expect(withWritten(comments, { ...nothingWritten(), deleted: ['1'] })).toEqual([comments[1]])
  })

  it('takes edits and deletions of comments that were added', () => {
    const added = comment('3')
    const edited = comment('3', 'Changed')
    expect(withWritten(comments, { added: [added], edited: [edited], deleted: [] }).at(-1)).toBe(edited)
    expect(withWritten(comments, { added: [added], edited: [], deleted: ['3'] })).toEqual(comments)
  })
})
