import { defineEventHandler, getRequestURL } from 'h3'
import { askUnleash } from '../../unleash'

// The flags that are on, for the context in the query (who's asking, as Unleash's SDK puts it there), as Unleash's
// frontend API says: for the pages the server renders and the browser (plugins/flags.ts)
export default defineEventHandler((event) => askUnleash(event, getRequestURL(event).search))
