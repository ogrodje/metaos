// Pictures MetaOS serves to everyone from its public bucket, as its GraphQL API has them
// (src/main/resources/graphql/schema.graphqls): an episode's thumbnails, web picture and cover, a person's avatar

export interface ImageSize {
  width: number
  url: string
}

/** A picture: the original, and smaller copies of it, the narrowest first, at URLs whose pictures never change */
export interface Image {
  // The file it was made from, e.g. ogrodje-vresGams-thumb1.png
  name: string
  url: string
  width: number
  height: number
  sizes: ImageSize[]
  // Of the original: the same file is the same picture
  sha256: string
}

/** The fields of an Image, for a query */
export const IMAGE_FIELDS = 'name url width height sizes { width url } sha256'

/**
 * For an <img>'s srcset: its copies, for the browser to pick the one that fits. The original only when there are none,
 * as it's as big as it came, e.g. a podcast cover of 16 MB.
 */
export function srcset(image: Image) {
  const sizes = image.sizes.length ? image.sizes : [{ width: image.width, url: image.url }]
  return sizes.map((size) => `${size.url} ${size.width}w`).join(', ')
}

/** For an <img>'s src, where srcset isn't understood: the narrowest copy at least [width] wide, or else the widest */
export function src(image: Image, width: number) {
  return image.sizes.find((size) => size.width >= width)?.url ?? image.sizes.at(-1)?.url ?? image.url
}
