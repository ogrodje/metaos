import { describe, expect, it } from 'vitest'
import { type EpisodeDetails, isRecorded, pictureParts } from '@/episodes'
import type { Image } from '@/images'

const image = { name: 'ogrodje-vresGams-thumb1.png' } as Image

const episode: Parameters<typeof pictureParts>[0] = {
  status: 'PUBLISHED', title: 'GaMS', titleB: 'Kako nastaja GaMS?', titleC: 'Slovenski LLM', thumbnail: image, thumbnailB: image,
  thumbnailC: image, web: null, cover: image,
}

describe('pictureParts', () => {
  it("says what an episode's pictures are, and what's missing once it's recorded", () => {
    expect(pictureParts(episode, 4, 9)).toEqual([
      { text: '3 titles tested' }, { text: 'cover' }, { text: 'no web picture', missing: true }, { text: '4 artwork files' },
      { text: '9 photos' },
    ])
  })

  it("misses the thumbnail of a title that hasn't one", () => {
    expect(pictureParts({ ...episode, thumbnailC: null }, 0, 0)).toContainEqual({ text: 'no thumbnail C', missing: true })
  })

  it('says its titles only when they are tested, as one is no news', () => {
    const one = { ...episode, titleB: null, titleC: null, thumbnailB: null, thumbnailC: null }
    expect(pictureParts(one, 0, 0)).toEqual([{ text: 'thumbnail' }, { text: 'cover' }, { text: 'no web picture', missing: true }])
    expect(pictureParts({ ...one, thumbnail: null }, 0, 0)).toContainEqual({ text: 'no thumbnail', missing: true })
  })

  it("doesn't miss what an idea doesn't have yet", () => {
    const idea: Pick<EpisodeDetails, 'status'> = { status: 'IDEA' }
    expect(pictureParts({ ...episode, ...idea, titleB: null, titleC: null, thumbnail: null, thumbnailB: null, thumbnailC: null,
      cover: null }, 0, 0)).toEqual([])
  })
})

describe('isRecorded', () => {
  it('is from the recording on', () => {
    expect(isRecorded('SCHEDULED')).toBe(false)
    expect(isRecorded('RECORDED')).toBe(true)
    expect(isRecorded('PUBLISHED')).toBe(true)
  })
})
