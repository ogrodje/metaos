// Sending a big file to a resumable upload of Cloud Storage's (SignedUpload in Storage.kt) a chunk at a time, going on
// from where it was after the connection broke: https://cloud.google.com/storage/docs/performing-resumable-uploads

/** A chunk of what's sent at a time: Cloud Storage takes multiples of 256 KiB, but for the last */
export const CHUNK_BYTES = 16 * 1024 * 1024

// How often a chunk is sent again after the connection broke, or Cloud Storage didn't answer, before giving up
const RETRIES = 6

/** Where to go on from, by how much Cloud Storage says it has, e.g. bytes=0-262143; from the start when it has none */
export function nextOffset(range: string | null): number {
  const end = range ? /^bytes=0-(\d+)$/.exec(range)?.[1] : undefined
  return end === undefined ? 0 : Number(end) + 1
}

/** A request, as Cloud Storage answered it: 0 for none, as when the connection broke */
export interface Answer {
  status: number
  range: string | null
}

/**
 * Sends [file] to an upload's [session], a chunk at a time, each saying where in the file it is. Once the connection
 * broke, or Cloud Storage didn't answer, it waits, asks how much it has, and goes on from there. [onSent] says how much
 * of it has been sent, as it goes.
 */
export async function sendInChunks(
  session: string, file: Blob, onSent: (bytes: number) => void, signal: AbortSignal, chunk = CHUNK_BYTES,
  send: (range: string, body: Blob | null, onLoaded: (bytes: number) => void) => Promise<Answer> =
    (range, body, onLoaded) => put(session, range, body, onLoaded, signal),
  pause: (attempt: number) => Promise<void> = (attempt) => wait(Math.min(30_000, 1000 * 2 ** (attempt - 1)), signal),
): Promise<void> {
  let offset = 0
  let failures = 0
  let asking = false
  for (;;) {
    const end = Math.min(offset + chunk, file.size)
    const answer = await (asking
      ? send(`bytes */${file.size}`, null, () => {})
      : send(`bytes ${offset}-${end - 1}/${file.size}`, file.slice(offset, end), (loaded) => onSent(offset + loaded))
    ).catch((error) => {
      if (signal.aborted) throw error
      return { status: 0, range: null }
    })
    if (answer.status === 200 || answer.status === 201) {
      onSent(file.size)
      return
    }
    if (answer.status === 308) {
      offset = nextOffset(answer.range)
      failures = 0
      asking = false
      onSent(offset)
      continue
    }
    if (answer.status === 404 || answer.status === 410) throw new Error('The upload expired; send the file again')
    // Anything else but a broken connection or Cloud Storage not managing, which a while later may go
    if (answer.status && answer.status !== 408 && answer.status !== 429 && answer.status < 500) {
      throw new Error(`Google Cloud Storage answered ${answer.status}`)
    }
    if (++failures > RETRIES) throw new Error('The connection kept breaking while it was being sent')
    await pause(failures)
    asking = true
  }
}

function wait(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Stopped', 'AbortError'))
    }, { once: true })
  })
}

// With XMLHttpRequest, as fetch doesn't tell how much of a body it has sent; Cloud Storage's 308 has no Location, so
// it isn't followed
function put(url: string, range: string, body: Blob | null, onLoaded: (bytes: number) => void, signal: AbortSignal) {
  return new Promise<Answer>((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', url)
    request.setRequestHeader('Content-Range', range)
    request.upload.onprogress = (event) => onLoaded(event.loaded)
    request.onload = () => resolve({ status: request.status, range: request.getResponseHeader('Range') })
    request.onerror = () => reject(new Error('The connection broke while it was being sent'))
    request.onabort = () => reject(new DOMException('Stopped', 'AbortError'))
    signal.addEventListener('abort', () => request.abort(), { once: true })
    request.send(body)
  })
}
