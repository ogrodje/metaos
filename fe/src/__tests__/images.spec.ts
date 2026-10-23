import { describe, expect, it } from 'vitest'
import { type Image, src, srcset } from '@/images'

const base = 'https://media.ogrodje.si/images/abc'

const cover: Image = {
  name: 'ogrodje-vresGams-podcast.png',
  url: `${base}/original.png`,
  width: 3000,
  height: 3000,
  sizes: [320, 640, 1280, 1920].map((width) => ({ width, url: `${base}/${width}.jpg` })),
  sha256: 'abc',
}

// Narrower than the narrowest copy, so there are none
const small: Image = { name: 'logo.png', url: `${base}/original.png`, width: 200, height: 200, sizes: [], sha256: 'abc' }

describe('srcset', () => {
  it('offers the copies, without the original', () => {
    expect(srcset(cover)).toBe(`${base}/320.jpg 320w, ${base}/640.jpg 640w, ${base}/1280.jpg 1280w, ${base}/1920.jpg 1920w`)
  })

  it('offers the original when there are no copies', () => {
    expect(srcset(small)).toBe(`${base}/original.png 200w`)
  })
})

describe('src', () => {
  it('takes the narrowest copy wide enough', () => {
    expect(src(cover, 400)).toBe(`${base}/640.jpg`)
    expect(src(cover, 640)).toBe(`${base}/640.jpg`)
  })

  it('takes the widest copy, rather than the original, when none is wide enough', () => {
    expect(src(cover, 2500)).toBe(`${base}/1920.jpg`)
  })

  it('takes the original when there are no copies', () => {
    expect(src(small, 400)).toBe(`${base}/original.png`)
  })
})
