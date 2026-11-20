package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.WebException

/** Read pages in order. WordPress answers 400 when a request goes past the last full page. */
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
