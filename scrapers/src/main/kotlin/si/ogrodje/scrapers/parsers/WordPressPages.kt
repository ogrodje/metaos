package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.WebException

/**
 * Read listing pages in order. Each [readPage] makes one listing request and decodes its items.
 * WordPress answers HTTP 400 for a page past the end of the collection.
 */
internal fun <T> wordPressPages(maxPages: Int, pageSize: Int, readPage: (Int) -> List<T>): List<T> = buildList {
    for (page in 1..maxPages) {
        val items = try {
            readPage(page)
        } catch (e: WebException) {
            if (page > 1 && e.status == 400) break
            throw e
        }
        addAll(items)
        if (items.size < pageSize) break
    }
}
