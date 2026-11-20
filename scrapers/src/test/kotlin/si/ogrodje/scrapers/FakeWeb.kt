package si.ogrodje.scrapers

import java.net.URI

/**
 * The web in tests: [pages] by URL (or by a URL's start, the longest that fits, e.g. with its query left out), else
 * 404. What was asked for is in [asked].
 */
class FakeWeb(private val pages: Map<String, String>) : Web {
    val asked = mutableListOf<String>()

    override fun get(url: URI, headers: Map<String, String>): String = answer(url.toString())

    override fun post(url: URI, body: String, contentType: String, headers: Map<String, String>): String = answer(url.toString())

    private fun answer(url: String): String {
        asked += url
        return pages[url] ?: pages.entries.filter { url.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            ?: throw WebException(404, "$url answered 404")
    }

    companion object {
        /** A file of src/test/resources/fixtures, e.g. what a site answered, saved */
        fun fixture(name: String): String =
            checkNotNull(FakeWeb::class.java.getResource("/fixtures/$name")) { "No fixture $name" }.readText()
    }
}

/** Replace one GET response while keeping the other fixture responses. */
fun Web.replaceGet(matches: (URI) -> Boolean, response: () -> String): Web = object : Web by this {
    override fun get(url: URI, headers: Map<String, String>): String =
        if (matches(url)) response() else this@replaceGet.get(url, headers)

    override fun json(url: URI, headers: Map<String, String>) = super<Web>.json(url, headers)
}

/** A context with [web] and no browser, for [organisation] */
fun context(web: Web, organisation: String = "Test") = ScrapeContext(organisation, web, null)
