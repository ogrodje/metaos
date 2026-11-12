import { describe, expect, it } from 'vitest'
import { channelOf, type Distribution, distributedAt, distributionLine, distributionTitle } from '@/distribution'

function entry(fields: Partial<Distribution> = {}): Distribution {
  return {
    id: 'd1', platform: null, channel: 'LINKEDIN', url: 'https://www.linkedin.com/posts/ogrodje_1', title: null, note: null,
    postedAt: null, addedBy: 'ana', createdAt: '2026-11-10T10:00:00Z',
    preview: { status: 'PENDING', title: null, description: null, site: null, publishedAt: null, image: null, error: null, fetchedAt: null },
    ...fields,
  }
}

describe('channelOf', () => {
  it('tells a channel by its host, a subdomain too, as MetaOS does', () => {
    expect(channelOf('https://youtu.be/abc')).toBe('YOUTUBE')
    expect(channelOf('https://old.reddit.com/r/slovenia')).toBe('REDDIT')
    expect(channelOf('https://twitter.com/ogrodje')).toBe('X')
    expect(channelOf('https://www.monitor.si/clanek/1')).toBe('MONITOR')
    expect(channelOf('https://notreddit.com/')).toBe('OTHER')
    expect(channelOf('not a link')).toBeNull()
  })
})

describe('distributionTitle', () => {
  it("is what someone named it, else its page's title, else its host and path", () => {
    expect(distributionTitle(entry({ title: 'Iva', preview: { ...entry().preview, title: 'Page' } }))).toBe('Iva')
    expect(distributionTitle(entry({ preview: { ...entry().preview, title: 'Page' } }))).toBe('Page')
    expect(distributionTitle(entry())).toBe('linkedin.com/posts/ogrodje_1')
  })
})

describe('distributedAt', () => {
  it('is when it went out as someone said, else as its page says, else when it was added', () => {
    expect(distributedAt(entry({ postedAt: '2026-11-09T00:00:00+01:00' }))).toBe('2026-11-09T00:00:00+01:00')
    expect(distributedAt(entry({ preview: { ...entry().preview, publishedAt: '2026-11-08T12:00:00Z' } }))).toBe('2026-11-08T12:00:00Z')
    expect(distributedAt(entry())).toBe('2026-11-10T10:00:00Z')
  })
})

describe('distributionLine', () => {
  it('says where its links are, then how many posts there are and where, the most first', () => {
    const line = distributionLine([
      entry({ id: 'l1', platform: 'YOUTUBE', channel: 'YOUTUBE' }),
      entry({ id: 'l2', platform: 'PATREON', channel: 'PATREON' }),
      entry({ id: 'l3', platform: 'PATREON_AUDIO', channel: 'PATREON' }),
      entry({ id: 'p1', channel: 'DISCORD' }),
      entry({ id: 'p2' }),
      entry({ id: 'p3' }),
    ])
    expect(line).toBe('On YouTube, Patreon · 3 posts: LinkedIn ×2, Discord')
  })

  it('says when it has no posts yet', () => {
    expect(distributionLine([])).toBe('Not shared yet')
    expect(distributionLine([entry({ platform: 'SPOTIFY', channel: 'SPOTIFY' })])).toBe('On Spotify · Not shared yet')
    expect(distributionLine([entry()])).toBe('1 post: LinkedIn')
  })
})
