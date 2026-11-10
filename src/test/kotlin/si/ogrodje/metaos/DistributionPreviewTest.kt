package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import io.temporal.client.WorkflowClient
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Previews parsed from pages, and where MetaOS fetches them from, without Spring */
class LinkPreviewsTest {

    @Test
    fun `reads a page's OpenGraph tags, else Twitter's, else its title`() {
        val page = Jsoup.parse(
            """
            <html><head><title>Fallback</title>
            <meta property="og:title" content=" Novi podcast ">
            <meta name="twitter:description" content="O lokalnih LLM-jih">
            <meta property="og:image" content="/pictures/cover.png">
            <meta property="article:published_time" content="2026-11-09T18:30:00+01:00">
            </head></html>
            """.trimIndent(),
            "https://www.linkedin.com/posts/1",
        )
        val preview = LinkPreviews.parse(page, "www.linkedin.com")
        assertEquals("Novi podcast", preview.title)
        assertEquals("O lokalnih LLM-jih", preview.description)
        assertEquals("linkedin.com", preview.site)
        assertEquals("https://www.linkedin.com/pictures/cover.png", preview.image)
        assertEquals("2026-11-09T18:30+01:00", preview.publishedAt.toString())

        val bare = LinkPreviews.parse(Jsoup.parse("<title>Only a title</title>", "https://example.com/"), "example.com")
        assertEquals("Only a title", bare.title)
        assertNull(bare.image)
    }

    @Test
    fun `fetches only from public addresses`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.100.1.1", "0.0.0.0", "::1", "fd12::1", "fe80::1")
            .forEach { assertFalse(isPublicAddress(InetAddress.getByName(it)), it) }
        listOf("1.1.1.1", "34.107.1.1", "100.128.0.1", "2a00:1450:4001::1").forEach { assertTrue(isPublicAddress(InetAddress.getByName(it)), it) }
    }

    @Test
    fun `tells channels by their hosts, and keeps URLs without their tracking`() {
        assertEquals(Channel.YOUTUBE, Channel.of("https://youtu.be/abc"))
        assertEquals(Channel.X, Channel.of("https://twitter.com/ogrodje/status/1"))
        assertEquals(Channel.REDDIT, Channel.of("https://old.reddit.com/r/slovenia"))
        assertEquals(Channel.OTHER, Channel.of("https://notreddit.com/"))
        assertEquals("https://youtu.be/abc?t=30", distributionUrl("https://youtu.be/abc?si=xyz&t=30"))
        assertEquals("https://example.com/a#b", distributionUrl(" https://Example.com:443/a?utm_campaign=x#b "))
    }
}

@SpringBootTest(
    properties = [
        // Temporal in memory, MetaOS its worker
        "spring.temporal.test-server.enabled=true",
        "metaos.storage.bucket=ogrodje-metaos-test", "metaos.media.bucket=ogrodje-metaos-public-test",
        // The pages are on a stand-in server on this machine
        "metaos.previews.allow-local=true",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class)
class DistributionPreviewTest @Autowired constructor(
    private val distributions: DistributionsRepository,
    private val episodes: EpisodesRepository,
    private val previews: DistributionPreviews,
    private val images: ImagesRepository,
    private val client: WorkflowClient,
) {

    companion object {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/post") { exchange ->
                val html = """
                    <html><head>
                    <meta property="og:title" content="Nova epizoda: Lokalni LLM-ji">
                    <meta property="og:description" content="Poslušajte">
                    <meta property="og:site_name" content="LinkedIn">
                    <meta property="og:image" content="/picture.png">
                    </head><body></body></html>
                """.trimIndent().encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            }
            createContext("/picture.png") { exchange ->
                val png = picture(1200, 630, "png")
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            createContext("/moved") { exchange ->
                exchange.responseHeaders.add("Location", "/post")
                exchange.sendResponseHeaders(301, -1)
                exchange.close()
            }
            createContext("/login") { exchange ->
                exchange.sendResponseHeaders(999, -1)
                exchange.close()
            }
            createContext("/empty") { exchange ->
                val html = "<html><body>Sign in</body></html>".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            }
            start()
        }

        private val base = "http://127.0.0.1:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun storageKey(registry: DynamicPropertyRegistry) {
            registry.add("metaos.storage.key", ::fakeServiceAccountKey)
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    // A post at the stand-in server's path, as Distributions keeps https ones; its preview fetched, and waited for
    private fun previewed(code: String, path: String): Distribution {
        val episode = episodes.findByCode(code) ?: episodes.saveAndFlush(Episode(title = "Predogled", code = code))
        val entry = distributions.saveAndFlush(Distribution(checkNotNull(episode.id), null, Channel.LINKEDIN, "$base$path", "ana-id", "ana"))
        val id = checkNotNull(entry.id)
        previews.start(id, Actor("ana-id", "ana"))
        client.newUntypedWorkflowStub(DistributionPreviewWorkflow.workflowId(id)).getResult(30, TimeUnit.SECONDS, Void::class.java)
        return checkNotNull(distributions.findByIdOrNull(id))
    }

    @Test
    fun `fetches a post's preview, through redirects, with its picture in the public bucket`() {
        val entry = previewed("pv-101", "/moved")
        assertEquals(PreviewStatus.OK, entry.previewStatus)
        assertEquals("Nova epizoda: Lokalni LLM-ji", entry.previewTitle)
        assertEquals("Poslušajte", entry.previewDescription)
        assertEquals("LinkedIn", entry.previewSite)
        assertNotNull(entry.previewedAt)
        val image = checkNotNull(images.findByIdOrNull(checkNotNull(entry.previewImageId)))
        assertEquals(1200, image.width)
        assertEquals("$base/picture.png", image.sourcePath)
    }

    @Test
    fun `says why there's no preview, without asking again what won't change`() {
        val login = previewed("pv-102", "/login")
        assertEquals(PreviewStatus.UNAVAILABLE, login.previewStatus)
        assertTrue(login.previewError!!.contains("999"), login.previewError)

        val empty = previewed("pv-102", "/empty")
        assertEquals(PreviewStatus.UNAVAILABLE, empty.previewStatus)
        assertTrue(empty.previewError!!.contains("may need signing in"), empty.previewError)
    }

    @Test
    fun `a preview is fetched again, keeping the one it had till then`() {
        val first = previewed("pv-103", "/post")
        val id = checkNotNull(first.id)
        first.repreview()
        distributions.saveAndFlush(first)
        previews.start(id, Actor(null, null))
        client.newUntypedWorkflowStub(DistributionPreviewWorkflow.workflowId(id)).getResult(30, TimeUnit.SECONDS, Void::class.java)
        val again = checkNotNull(distributions.findByIdOrNull(id))
        assertEquals(PreviewStatus.OK, again.previewStatus)
        // The same picture, stored once
        assertEquals(first.previewImageId, again.previewImageId)
    }
}
