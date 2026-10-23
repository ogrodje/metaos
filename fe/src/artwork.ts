// An episode's artwork, uploaded from its page (ArtworkUpload.vue): each of its pictures is PNG or JPEG of exactly its
// size, as the Artwork folder in Dropbox has them (EpisodeImage in Images.kt). The browser reads each file's size from
// its header and places it by its size and name before anything is sent; MetaOS checks the size again.

export type ArtworkKind = 'THUMBNAIL' | 'THUMBNAIL_B' | 'THUMBNAIL_C' | 'WEB' | 'COVER'

export type ArtworkType = 'image/png' | 'image/jpeg'

export interface Slot {
  kind: ArtworkKind
  // The episode's field with it (GraphQL)
  field: 'thumbnail' | 'thumbnailB' | 'thumbnailC' | 'web' | 'cover'
  label: string
  width: number
  height: number
}

/** The episode's pictures, as the dialog lists them */
export const SLOTS: Slot[] = [
  { kind: 'THUMBNAIL', field: 'thumbnail', label: 'Thumbnail A', width: 1920, height: 1080 },
  { kind: 'THUMBNAIL_B', field: 'thumbnailB', label: 'Thumbnail B', width: 1920, height: 1080 },
  { kind: 'THUMBNAIL_C', field: 'thumbnailC', label: 'Thumbnail C', width: 1920, height: 1080 },
  { kind: 'WEB', field: 'web', label: 'Web picture', width: 1920, height: 1080 },
  { kind: 'COVER', field: 'cover', label: 'Podcast cover', width: 3000, height: 3000 },
]

export const slotOf = (kind: ArtworkKind) => SLOTS.find((slot) => slot.kind === kind)!

/** A slot's size, as people read it, e.g. 1920 × 1080 */
export const sizeOf = (slot: Pick<Slot, 'width' | 'height'>) => `${slot.width} × ${slot.height}`

// Files a folder has that are pictures, which may be the wrong kind; anything else, e.g. .DS_Store, is left out
const PICTURE = /\.(png|jpe?g|webp|gif|heic|heif|avif|tiff?|bmp)$/i

/** A file the user picked, as the browser read it: its type and size from its header, none when it isn't PNG or JPEG */
export interface Picked {
  id: number
  file: File
  name: string
  type: ArtworkType | null
  width: number | null
  height: number | null
  // Of the whole file, as MetaOS names its pictures by it: the same file is the same picture
  sha256: string | null
}

/** Whether a file is a picture at all, by its name: anything else in a folder is left out */
export const isPicture = (file: Pick<File, 'name'>) => PICTURE.test(file.name)

/**
 * Its type and size, from its header: a PNG's IHDR, or a JPEG's start of frame. Null when it's neither, e.g. a WebP.
 * As written, not as an EXIF orientation would turn it.
 */
export function readHeader(bytes: Uint8Array): { type: ArtworkType, width: number, height: number } | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length >= 24 && png.every((byte, i) => bytes[i] === byte)) {
    return { type: 'image/png', width: view.getUint32(16), height: view.getUint32(20) }
  }
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let at = 2
  while (at + 9 < bytes.length) {
    if (bytes[at] !== 0xff) return null
    const marker = bytes[at + 1]!
    // Fill bytes before a marker
    if (marker === 0xff) {
      at++
      continue
    }
    // Markers without a length
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
      at += 2
      continue
    }
    // Start of frame, of any kind but DHT, JPG and DAC, which share the range
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { type: 'image/jpeg', width: view.getUint16(at + 7), height: view.getUint16(at + 5) }
    }
    at += 2 + view.getUint16(at + 2)
  }
  return null
}

// Enough for a JPEG's EXIF and other segments before its frame
const HEADER_BYTES = 1024 * 1024

let ids = 0

/** A file as the browser reads it: its header, and its hash only when it's PNG or JPEG */
export async function readPicked(file: File): Promise<Picked> {
  const header = readHeader(new Uint8Array(await file.slice(0, HEADER_BYTES).arrayBuffer()))
  const sha256 = header ? hex(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())) : null
  return { id: ++ids, file, name: file.name, type: header?.type ?? null, width: header?.width ?? null, height: header?.height ?? null, sha256 }
}

const hex = (buffer: ArrayBuffer) => [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('')

/** Why a picture can't be this one of the episode's, or null when it can */
export function refusal(picked: Picked, slot: Slot): string | null {
  if (!picked.type) return `It isn't PNG or JPEG, which the ${slot.label.toLowerCase()} has to be.`
  if (picked.width === slot.width && picked.height === slot.height) return null
  return `It's ${picked.width} × ${picked.height}; the ${slot.label.toLowerCase()} has to be ${sizeOf(slot)}.`
}

/** Why a picture can't be any of the episode's, or null when it can be one */
export function anyRefusal(picked: Picked): string | null {
  if (!picked.type) return "It isn't PNG or JPEG."
  if (SLOTS.some((slot) => slot.width === picked.width && slot.height === picked.height)) return null
  return `It's ${picked.width} × ${picked.height}; thumbnails and the web picture are 1920 × 1080, the cover 3000 × 3000.`
}

/**
 * Which picture its name says it is, as the Artwork folder names them: ...-thumbnail, -thumb, -thumb1 or -thumb-1 is
 * thumbnail A, -thumb2 B and -thumb3 C, -web the web picture, -podcast or -cover the cover. Null when it says none.
 */
export function kindByName(name: string): ArtworkKind | null {
  const words = name.replace(/\.[^.]*$/, '').toLowerCase().split(/[-_ .]+/)
  if (words.some((word) => word === 'podcast' || word === 'cover')) return 'COVER'
  if (words.includes('web')) return 'WEB'
  for (const [i, word] of words.entries()) {
    const thumb = /^thumb(?:nail)?([123])?$/.exec(word)
    if (!thumb) continue
    const number = thumb[1] ?? (/^[123]$/.test(words[i + 1] ?? '') ? words[i + 1] : '1')
    return number === '1' ? 'THUMBNAIL' : number === '2' ? 'THUMBNAIL_B' : 'THUMBNAIL_C'
  }
  return null
}

export interface Placement {
  // Where each goes, and why there: its name, or its size, the cover's being the only square one
  placed: { kind: ArtworkKind, picked: Picked, by: 'name' | 'size' }[]
  // Of a size that fits, but nothing said where, or another took its place
  unplaced: Picked[]
  // What can't be any of the episode's pictures, and why
  refused: { picked: Picked, reason: string }[]
}

/**
 * Where pictures dropped together go, e.g. an episode's folder: each by its name, where it's the size of that one; the
 * cover's size alone is enough for the cover, when nothing else is it. The first, by name, of those that say the same.
 * [taken] are the places that already have something picked, which these don't take from them.
 */
export function placeAll(pictures: Picked[], taken: ArtworkKind[] = []): Placement {
  const placement: Placement = { placed: [], unplaced: [], refused: [] }
  const free = (kind: ArtworkKind) => !taken.includes(kind) && !placement.placed.some((it) => it.kind === kind)
  const fitting: Picked[] = []
  for (const picked of [...pictures].sort((a, b) => a.name.localeCompare(b.name))) {
    const reason = anyRefusal(picked)
    if (reason) placement.refused.push({ picked, reason })
    else fitting.push(picked)
  }
  const unnamed: Picked[] = []
  for (const picked of fitting) {
    const kind = kindByName(picked.name)
    if (kind && !refusal(picked, slotOf(kind)) && free(kind)) placement.placed.push({ kind, picked, by: 'name' })
    else unnamed.push(picked)
  }
  for (const picked of unnamed) {
    const cover = slotOf('COVER')
    if (!refusal(picked, cover) && free('COVER') && !kindByName(picked.name)) placement.placed.push({ kind: 'COVER', picked, by: 'size' })
    else placement.unplaced.push(picked)
  }
  placement.placed.sort((a, b) => SLOTS.indexOf(slotOf(a.kind)) - SLOTS.indexOf(slotOf(b.kind)))
  return placement
}

/** The files in what was dropped: files as they are, folders with everything in them, all the way down */
export async function droppedFiles(items: DataTransferItemList): Promise<{ files: File[], folder: string | null }> {
  const entries = [...items].map((item) => item.webkitGetAsEntry?.()).filter((entry): entry is FileSystemEntry => !!entry)
  // Without entries, e.g. in an older browser, the files that came
  if (!entries.length) return { files: [...items].map((item) => item.getAsFile()).filter((file): file is File => !!file), folder: null }
  const folder = entries.length === 1 && entries[0]!.isDirectory ? entries[0]!.name : null
  const files: File[] = []
  async function walk(entry: FileSystemEntry): Promise<void> {
    if (entry.isFile) {
      files.push(await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject)))
    } else if (entry.isDirectory) {
      const reader = (entry as FileSystemDirectoryEntry).createReader()
      // A folder is read a batch at a time, until a batch is empty
      for (;;) {
        const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
        if (!batch.length) break
        for (const child of batch) await walk(child)
      }
    }
  }
  for (const entry of entries) await walk(entry)
  return { files, folder }
}
