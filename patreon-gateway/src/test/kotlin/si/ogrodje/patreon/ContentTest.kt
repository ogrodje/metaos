package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class ContentTest {
    private val mapper = JsonMapper.builder().build()
    private val web = PatreonWeb(
        Browser(PatreonProperties(), mapper),
        PatreonApi(PatreonProperties(), TokenStore(PatreonProperties(), mapper), mapper, java.time.Clock.systemUTC()),
        Media(PatreonProperties()),
        mapper,
    )

    @Test
    fun `paragraphs, lines and links become the editor's HTML and document`() {
        val (html, json) = web.content("Prvi odstavek & <več>.\nNova vrstica.\n\nPoslušaj na https://ogrodje.si/s5e8, hvala!")
        assertEquals(
            "<p>Prvi odstavek &amp; &lt;več&gt;.<br>Nova vrstica.</p>" +
                "<p>Poslušaj na <a href=\"https://ogrodje.si/s5e8\" target=\"_blank\">https://ogrodje.si/s5e8</a>, hvala!</p>",
            html,
        )
        val doc = mapper.readTree(json)
        assertEquals("doc", doc["type"].asString())
        val first = doc["content"][0]["content"]
        assertEquals(listOf("text", "hardBreak", "text"), first.toList().map { it["type"].asString() })
        val link = doc["content"][1]["content"][1]
        assertEquals("https://ogrodje.si/s5e8", link["text"].asString())
        assertEquals("https://ogrodje.si/s5e8", link["marks"][0]["attrs"]["href"].asString())
        assertEquals(", hvala!", doc["content"][1]["content"][2]["text"].asString())
    }

    @Test
    fun `no text is an empty document`() {
        val (html, json) = web.content("  ")
        assertEquals("", html)
        assertEquals("""{"type":"doc","content":[]}""", json)
    }
}
