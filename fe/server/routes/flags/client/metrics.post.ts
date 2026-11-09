import { createError, defineEventHandler, readRawBody } from 'h3'
import { askUnleash } from '../../../unleash'

// More than the SDK's counts of a few flags ever are
const MAX_BODY = 64 * 1024

// How often the browser asked for each flag, which Unleash's SDK sends, so its UI says which flags are in use
export default defineEventHandler(async (event) => {
  const body = (await readRawBody(event)) ?? '{}'
  if (body.length > MAX_BODY) throw createError({ statusCode: 413 })
  return askUnleash(event, '/client/metrics', { method: 'POST', body })
})
