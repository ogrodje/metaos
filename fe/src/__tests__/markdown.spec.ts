import { describe, expect, it } from 'vitest'
import { imageAllowed, renderMarkdown } from '@/markdown'

describe('renderMarkdown', () => {
  it('shows pictures from MetaOS, Dropbox and Koofr', () => {
    for (const src of [
      'https://media.ogrodje.si/images/abc/640.jpg',
      'https://dl.dropboxusercontent.com/scl/fi/abc/cover.png',
      'https://www.dropbox.com/scl/fi/abc/cover.png?raw=1',
      'https://app.koofr.net/content/links/abc/files/get/cover.png',
    ]) expect(renderMarkdown(`![cover](${src})`)).toContain(`<img src="${src.replace('&', '&amp;')}" alt="cover">`)
  })

  it('turns a picture from anywhere else into a link, so nothing loads by itself', () => {
    const html = renderMarkdown('Look: ![secret](https://example.com/p.png?d=private)')
    expect(html).not.toContain('<img')
    expect(html).toContain('<a href="https://example.com/p.png?d=private" target="_blank" rel="noopener noreferrer">secret</a>')
  })

  it('takes the host exactly, over https only', () => {
    expect(imageAllowed('https://media.ogrodje.si/a.png')).toBe(true)
    expect(imageAllowed('http://media.ogrodje.si/a.png')).toBe(false)
    expect(imageAllowed('https://media.ogrodje.si.example.com/a.png')).toBe(false)
    expect(imageAllowed('https://media.ogrodje.si@example.com/a.png')).toBe(false)
    expect(imageAllowed('https://user@media.ogrodje.si/a.png')).toBe(false)
    expect(imageAllowed('https://evilmedia.ogrodje.si/a.png')).toBe(false)
    expect(imageAllowed('/dropbox/thumbnail?place=1&path=/a.png')).toBe(false)
    expect(imageAllowed('data:image/png;base64,AAAA')).toBe(false)
  })

  it('escapes what goes into a link made of a picture', () => {
    const html = renderMarkdown('![<b>x</b>](https://example.com/a.png?q="><script>)')
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('<b>')
  })

  it('keeps raw HTML as text and opens links in a tab of their own', () => {
    expect(renderMarkdown('<img src=x onerror=alert(1)>')).toContain('&lt;img')
    expect(renderMarkdown('ogrodje.si')).toContain('target="_blank" rel="noopener noreferrer"')
    expect(renderMarkdown('[x](javascript:alert(1))')).not.toContain('href="javascript')
  })
})
