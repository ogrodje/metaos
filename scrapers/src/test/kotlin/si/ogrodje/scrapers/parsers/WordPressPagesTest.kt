package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.WebException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WordPressPagesTest {
    @Test
    fun `short and empty pages each stop immediately`() {
        for (items in listOf(listOf(1), emptyList())) {
            val result = wordPressPages(3, 2) { page ->
                assertEquals(1, page)
                items
            }
            assertEquals(items, result)
        }
    }

    @Test
    fun `full pages stop at the configured limit`() {
        assertEquals(listOf(1, 1, 2, 2), wordPressPages(2, 2) { page -> listOf(page, page) })
    }

    @Test
    fun `a 400 after a full page returns the collected items in order`() {
        val requests = mutableListOf<Int>()
        val items = wordPressPages(4, 2) { page ->
            requests += page
            if (page == 3) throw WebException(400, "Past the last page")
            listOf(page * 2 - 1, page * 2)
        }
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(listOf(1, 2, 3, 4), items)
    }

    @Test
    fun `a first page 400 and a later server failure propagate`() {
        assertEquals(400, assertFailsWith<WebException> {
            wordPressPages<Int>(2, 1) { throw WebException(400, "Invalid query") }
        }.status)
        assertEquals(503, assertFailsWith<WebException> {
            wordPressPages(2, 1) { page ->
                if (page == 2) throw WebException(503, "Unavailable")
                listOf(page)
            }
        }.status)
    }
}
