import { describe, expect, it } from 'vitest'
import { anyRefusal, kindByName, type Picked, placeAll, readHeader, refusal, slotOf } from '@/artwork'

let ids = 0

function picked(name: string, width: number | null, height: number | null, type: Picked['type'] = 'image/png'): Picked {
  return { id: ++ids, file: new File([], name), name, type, width, height, sha256: `sha-${name}` }
}

const thumbnail = (name: string) => picked(name, 1920, 1080)
const cover = (name: string) => picked(name, 3000, 3000)

function png(width: number, height: number) {
  const bytes = new Uint8Array(33)
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52])
  const view = new DataView(bytes.buffer)
  view.setUint32(16, width)
  view.setUint32(20, height)
  return bytes
}

// A JPEG's start, with an APP0 segment before its frame (SOF0, or SOF2 for a progressive one)
function jpeg(width: number, height: number, frame = 0xc0) {
  const app0 = [0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]
  const sof = [0xff, frame, 0x00, 0x11, 0x08, height >> 8, height & 0xff, width >> 8, width & 0xff, 0x03, 0x01, 0x22, 0x00]
  return new Uint8Array([0xff, 0xd8, ...app0, ...sof, 0, 0, 0, 0])
}

describe('readHeader', () => {
  it("reads a PNG's and a JPEG's type and size", () => {
    expect(readHeader(png(1920, 1080))).toEqual({ type: 'image/png', width: 1920, height: 1080 })
    expect(readHeader(jpeg(3000, 3000))).toEqual({ type: 'image/jpeg', width: 3000, height: 3000 })
    expect(readHeader(jpeg(1920, 1080, 0xc2))).toEqual({ type: 'image/jpeg', width: 1920, height: 1080 })
  })

  it("is null for what isn't PNG or JPEG", () => {
    expect(readHeader(new TextEncoder().encode('RIFF\0\0\0\0WEBPVP8 '))).toBeNull()
    expect(readHeader(new TextEncoder().encode('# Naslovnica'))).toBeNull()
    expect(readHeader(new Uint8Array([0xff, 0xd8]))).toBeNull()
  })
})

describe('kindByName', () => {
  it('places the Artwork folder\'s files by their names', () => {
    expect(kindByName('ogrodje-armbian-thumbnail.png')).toBe('THUMBNAIL')
    expect(kindByName('ogrodje-vresGams-thumb1.png')).toBe('THUMBNAIL')
    expect(kindByName('ogrodje-jureCuhalev-thumb-1.png')).toBe('THUMBNAIL')
    expect(kindByName('ogrodje-jureCuhalev-thumb-2.png')).toBe('THUMBNAIL_B')
    expect(kindByName('ogrodje-zidar-thumb3.png')).toBe('THUMBNAIL_C')
    expect(kindByName('ogrodje-MooHero-web.png')).toBe('WEB')
    expect(kindByName('ogrodje-mooHero-podcast.png')).toBe('COVER')
    expect(kindByName('S05E09 cover.jpg')).toBe('COVER')
  })

  it("says nothing for a name that doesn't say", () => {
    expect(kindByName('bonus.png')).toBeNull()
    expect(kindByName('ogrodje-webinar.png')).toBeNull()
    expect(kindByName('thumbs-up.png')).toBeNull()
  })
})

describe('refusal', () => {
  it('says what a picture is and what its place needs', () => {
    expect(refusal(thumbnail('a.png'), slotOf('WEB'))).toBeNull()
    expect(refusal(cover('ogrodje-zidar-podcast.png'), slotOf('WEB'))).toBe("It's 3000 × 3000; the web picture has to be 1920 × 1080.")
    expect(refusal(picked('a.webp', null, null, null), slotOf('COVER'))).toBe("It isn't PNG or JPEG, which the podcast cover has to be.")
    expect(anyRefusal(picked('Anton Babenko.png', 460, 460))).toBe(
      "It's 460 × 460; thumbnails and the web picture are 1920 × 1080, the cover 3000 × 3000.",
    )
    expect(anyRefusal(cover('bonus.png'))).toBeNull()
  })
})

describe('placeAll', () => {
  const kinds = (placement: ReturnType<typeof placeAll>) => placement.placed.map(({ kind, picked, by }) => [kind, picked.name, by])

  it('places an episode\'s folder by its names', () => {
    const placement = placeAll([
      cover('ogrodje-zidar-podcast.png'), thumbnail('ogrodje-zidar-thumb3.png'), thumbnail('ogrodje-zidar-thumb1.png'),
      thumbnail('ogrodje-zidar-thumb2.png'),
    ])
    expect(kinds(placement)).toEqual([
      ['THUMBNAIL', 'ogrodje-zidar-thumb1.png', 'name'],
      ['THUMBNAIL_B', 'ogrodje-zidar-thumb2.png', 'name'],
      ['THUMBNAIL_C', 'ogrodje-zidar-thumb3.png', 'name'],
      ['COVER', 'ogrodje-zidar-podcast.png', 'name'],
    ])
    expect(placement.unplaced).toEqual([])
    expect(placement.refused).toEqual([])
  })

  it("leaves what its name doesn't place for the user, and refuses what can't be any", () => {
    const placement = placeAll([
      thumbnail('ogrodje-MooHero-thumbnail.png'), thumbnail('ogrodje-MooHero-web.png'), cover('ogrodje-mooHero-podcast.png'),
      cover('bonus.png'), thumbnail('ogrodje-MooHero-thumb-1.png'), picked('ogrodje-MooHero-story.png', 1080, 1920),
      picked('ogrodje-MooHero.webp', null, null, null),
    ])
    expect(kinds(placement)).toEqual([
      ['THUMBNAIL', 'ogrodje-MooHero-thumb-1.png', 'name'],
      ['WEB', 'ogrodje-MooHero-web.png', 'name'],
      ['COVER', 'ogrodje-mooHero-podcast.png', 'name'],
    ])
    // Its name says thumbnail A, which another took first
    expect(placement.unplaced.map((it) => it.name)).toEqual(['bonus.png', 'ogrodje-MooHero-thumbnail.png'])
    expect(placement.refused.map((it) => it.picked.name)).toEqual(['ogrodje-MooHero-story.png', 'ogrodje-MooHero.webp'])
  })

  it("makes the only square one the cover, and doesn't take a place that's taken", () => {
    expect(kinds(placeAll([cover('final.png')]))).toEqual([['COVER', 'final.png', 'size']])
    expect(kinds(placeAll([cover('final.png'), cover('ogrodje-x-podcast.png')]))).toEqual([['COVER', 'ogrodje-x-podcast.png', 'name']])

    const placement = placeAll([thumbnail('ogrodje-x-thumb1.png'), cover('final.png')], ['THUMBNAIL', 'COVER'])
    expect(placement.placed).toEqual([])
    expect(placement.unplaced.map((it) => it.name)).toEqual(['final.png', 'ogrodje-x-thumb1.png'])
  })

  it("doesn't place a picture by its name where it isn't that one's size", () => {
    const placement = placeAll([thumbnail('ogrodje-x-podcast.png')])
    expect(placement.placed).toEqual([])
    expect(placement.unplaced.map((it) => it.name)).toEqual(['ogrodje-x-podcast.png'])
  })
})
