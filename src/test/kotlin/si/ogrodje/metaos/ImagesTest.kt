package si.ogrodje.metaos

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A bucket in memory, which remembers what its files are served with */
class FakeStorage(override val bucket: String = "fake") : Storage {
    class Stored(val file: StoredFile, val bytes: ByteArray, val cacheControl: String?)

    val files = mutableMapOf<String, Stored>()
    var uploads = 0

    override fun file(path: String) = files[storagePath(path)]?.file
    override fun list(folder: String, limit: Int) =
        files.values.map { it.file }.filter { folder.trim('/').isEmpty() || it.path.startsWith(storagePath(folder) + "/") }.take(limit)

    override fun upload(path: String, contentType: String, content: InputStream, overwrite: Boolean, cacheControl: String?): StoredFile {
        require(overwrite || storagePath(path) !in files) { "$path is already there" }
        val bytes = content.readAllBytes()
        uploads++
        return StoredFile(storagePath(path), bytes.size.toLong(), contentType, OffsetDateTime.now())
            .also { files[it.path] = Stored(it, bytes, cacheControl) }
    }

    override fun read(path: String, maxBytes: Int) = requireNotNull(files[storagePath(path)]).bytes.let { it.copyOf(minOf(it.size, maxBytes)) }
    override fun uploadUrl(path: String, contentType: String, validFor: Duration) = SignedUpload(
        URI("https://storage.example.com/$bucket/${storagePath(path)}?X-Goog-Signature=fake").toURL(),
        mapOf("Content-Type" to contentType, "x-goog-resumable" to "start"), Instant.now() + validFor,
    )
    override fun downloadUrl(path: String, validFor: Duration) = throw UnsupportedOperationException()
    override fun delete(path: String) = files.remove(storagePath(path)) != null
}

/** A service account's key as Google makes them, with a private key Google never issued: enough to start, not to call */
fun fakeServiceAccountKey(): String {
    val pem = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
        .let { "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(it) + "\n-----END PRIVATE KEY-----\n" }
    return JsonMapper.builder().build().writeValueAsString(
        mapOf(
            "type" to "service_account", "project_id" to "ogrodje-metaos", "private_key_id" to "0".repeat(40), "private_key" to pem,
            "client_email" to "metaos-storage-test@ogrodje-metaos.iam.gserviceaccount.com", "client_id" to "1",
            "token_uri" to "https://oauth2.googleapis.com/token",
        )
    )
}

/** A picture of [width] × [height]: one colour, and see-through on the left half if [seeThrough] */
fun picture(width: Int, height: Int, format: String, seeThrough: Boolean = false, color: Color = Color(4, 4, 164)): ByteArray {
    val image = BufferedImage(width, height, if (seeThrough) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        this.color = color
        fillRect(if (seeThrough) width / 2 else 0, 0, width, height)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

// A PNG that says it's 9000 × 9000, with nothing in it
private fun hugePngHeader(): ByteArray {
    fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + body + ByteBuffer.allocate(4).putInt(crc).array()
    }
    val header = ByteBuffer.allocate(13).putInt(9000).putInt(9000).put(8).put(2).put(0).put(0).put(0).array()
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", header) + chunk("IEND", byteArrayOf())
}

private fun decoded(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

@SpringBootTest(
    properties = [
        "metaos.dropbox.app-key=key", "metaos.dropbox.app-secret=secret", "metaos.dropbox.refresh-token=test",
        "metaos.koofr.email=iva@example.com", "metaos.koofr.app-password=test",
        "metaos.storage.bucket=ogrodje-metaos-test", "metaos.media.bucket=ogrodje-metaos-public-test",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class)
class ImagesTest @Autowired constructor(
    private val dropbox: FakeDropbox,
    private val koofr: FakeKoofr,
    private val media: Media,
    private val mcp: ImagesMCP,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val history: History,
    private val mvc: MockMvc,
) {

    companion object {
        // Raw, as it has a PEM's escaped newlines, which inline properties would read as newlines
        @JvmStatic
        @DynamicPropertySource
        fun storageKey(registry: DynamicPropertyRegistry) {
            registry.add("metaos.storage.key", ::fakeServiceAccountKey)
        }
    }

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun fakeDropbox() = FakeDropbox().apply { place("2001", "Artwork") }

        @Bean
        @Primary
        fun fakeKoofr() = FakeKoofr().apply { place("koofr-1", "Ogrodje") }

        @Bean
        @Primary
        fun fakeStorage(): Storage = FakeStorage()

        @Bean
        @Primary
        fun fakeMedia() = Media(FakeStorage("public"), "https://media.example.com/")
    }

    private val bucket get() = media.storage as FakeStorage

    @Test
    fun `copies artwork from Dropbox to the public bucket, with smaller copies, once however often it's used`() {
        val episode = episodes.saveAndFlush(Episode(title = "Vres gams", code = "S09E70"))
        val png = picture(1920, 1080, "png")
        dropbox.put("2001", "/Season 9/S09E70", null)
        dropbox.put("2001", "/Season 9/S09E70/ogrodje-vresGams-thumb1.png", png)

        val set = mcp.setEpisodeImage("S09E70", EpisodeImage.THUMBNAIL, ImageSource.DROPBOX, "Artwork", "Season 9/S09E70/ogrodje-vresGams-thumb1.png", null)
        val image = assertNotNull(set.image)
        assertEquals(EpisodeImage.THUMBNAIL, set.kind)
        assertEquals("ogrodje-vresGams-thumb1.png", image.name)
        assertEquals(1920 to 1080, image.width to image.height)
        assertEquals(ImageSource.DROPBOX to "2001", image.from to image.place)
        assertEquals("/Season 9/S09E70/ogrodje-vresGams-thumb1.png", image.path)
        assertTrue(image.url.matches(Regex("https://media\\.example\\.com/images/[0-9a-f]{64}/original\\.png")), image.url)
        assertEquals(listOf(320, 640, 1280), image.sizes.map { it.width })

        // The original as it came; JPEGs of the same shape, as it's opaque; all cached for good
        val folder = image.url.removePrefix("https://media.example.com/").substringBeforeLast('/')
        assertContentEquals(png, bucket.files.getValue("$folder/original.png").bytes)
        image.sizes.forEach { size ->
            assertEquals("${media.url}/$folder/${size.width}.jpg", size.url)
            val copy = bucket.files.getValue("$folder/${size.width}.jpg")
            assertEquals("image/jpeg", copy.file.contentType)
            val decoded = decoded(copy.bytes)
            assertEquals(size.width to Math.round(size.width * 1080 / 1920.0).toInt(), decoded.width to decoded.height)
        }
        assertEquals(setOf("public, max-age=31536000, immutable"), bucket.files.values.map { it.cacheControl }.toSet())

        // The same picture again, under another name, is the same image: nothing more is uploaded
        val uploads = bucket.uploads
        dropbox.put("2001", "/Season 9/S09E70/copy.png", png)
        val again = mcp.setEpisodeImage("S09E70", EpisodeImage.THUMBNAIL_B, ImageSource.DROPBOX, "2001", "/Season 9/S09E70/copy.png", null)
        assertEquals(image, again.image)
        assertEquals(uploads, bucket.uploads)

        assertEquals(
            listOf(
                EpisodeImage.THUMBNAIL to image.url, EpisodeImage.THUMBNAIL_B to image.url, EpisodeImage.THUMBNAIL_C to null,
                EpisodeImage.WEB to null, EpisodeImage.COVER to null,
            ),
            mcp.listEpisodeImages(checkNotNull(episode.id).toString()).map { it.kind to it.image?.url },
        )
    }

    @Test
    fun `keeps see-through pictures PNGs, and makes no copy as wide as the original`() {
        val person = people.saveAndFlush(Person(name = "Logo", email = null))
        koofr.put("koofr-1", "/Ljudje/logo.png", picture(500, 500, "png", seeThrough = true), "image/png")

        val image = mcp.setPersonAvatar(checkNotNull(person.id), ImageSource.KOOFR, "Ogrodje", "/Ljudje/logo.png", null)
        assertEquals(listOf(320), image.sizes.map { it.width })
        assertTrue(image.sizes.single().url.endsWith("/320.png"))
        val copy = decoded(bucket.files.getValue(image.sizes.single().url.removePrefix("${media.url}/")).bytes)
        assertEquals(320 to 320, copy.width to copy.height)
        // Still see-through where it was, and not where it wasn't
        assertEquals(0, copy.getRGB(10, 160) ushr 24)
        assertEquals(255, copy.getRGB(310, 160) ushr 24)
        assertEquals(ImageSource.KOOFR to "koofr-1", image.from to image.place)
    }

    @Test
    fun `refuses what isn't a picture, ones too big, and URLs other than Hygraph's`() {
        episodes.saveAndFlush(Episode(title = "Refused", code = "S09E72"))
        dropbox.put("2001", "/S09E72", null)
        dropbox.put("2001", "/S09E72/brief.md", "# Naslovnica".toByteArray())
        dropbox.put("2001", "/S09E72/huge.png", hugePngHeader())
        val uploads = bucket.uploads

        fun set(from: ImageSource, path: String? = null, url: String? = null) =
            assertFailsWith<IllegalArgumentException> { mcp.setEpisodeImage("S09E72", EpisodeImage.THUMBNAIL, from, "Artwork", path, url) }.message.orEmpty()

        assertTrue("isn't a picture" in set(ImageSource.DROPBOX, "/S09E72/brief.md"))
        assertTrue("9000 × 9000" in set(ImageSource.DROPBOX, "/S09E72/huge.png"))
        assertTrue("is a folder" in set(ImageSource.DROPBOX, "/S09E72"))
        assertTrue("There's no" in set(ImageSource.DROPBOX, "/S09E72/nope.png"))
        assertTrue("needs its path" in set(ImageSource.DROPBOX))
        assertTrue("needs the url" in set(ImageSource.URL))
        assertTrue("only from Hygraph" in set(ImageSource.URL, url = "https://example.com/avatar.png"))
        assertEquals(uploads, bucket.uploads)
        assertNull(episodes.findByCode("S09E72")?.thumbnailImageId)

        assertTrue(isImageUrl("https://eu-central-1-shared-euc1-02.graphassets.com/abc/def"))
        assertFalse(isImageUrl("http://eu-central-1-shared-euc1-02.graphassets.com/abc/def"))
        assertFalse(isImageUrl("https://graphassets.com.example.com/abc"))
        assertFalse(isImageUrl("https://notgraphassets.com/abc"))
    }

    @Test
    fun `refuses an episode's pictures of another size than it says, or not PNG or JPEG, wherever they come from`() {
        episodes.saveAndFlush(Episode(title = "Sizes", code = "S09E75"))
        dropbox.put("2001", "/S09E75", null)
        dropbox.put("2001", "/S09E75/ogrodje-sizes-thumb1.png", picture(1280, 720, "png"))
        dropbox.put("2001", "/S09E75/ogrodje-sizes-podcast.png", picture(3000, 3000, "png"))
        dropbox.put("2001", "/S09E75/ogrodje-sizes-web.gif", picture(1920, 1080, "gif"))
        val uploads = bucket.uploads

        fun refused(kind: EpisodeImage, path: String) =
            assertFailsWith<IllegalArgumentException> { mcp.setEpisodeImage("S09E75", kind, ImageSource.DROPBOX, "Artwork", path, null) }.message

        assertEquals(
            "ogrodje-sizes-thumb1.png is 1280 × 720; an episode's thumbnail has to be exactly 1920 × 1080",
            refused(EpisodeImage.THUMBNAIL, "/S09E75/ogrodje-sizes-thumb1.png"),
        )
        assertEquals(
            "ogrodje-sizes-podcast.png is 3000 × 3000; an episode's web picture has to be exactly 1920 × 1080",
            refused(EpisodeImage.WEB, "/S09E75/ogrodje-sizes-podcast.png"),
        )
        assertEquals(
            "ogrodje-sizes-web.gif isn't PNG or JPEG, which an episode's web picture has to be",
            refused(EpisodeImage.WEB, "/S09E75/ogrodje-sizes-web.gif"),
        )
        assertEquals(uploads, bucket.uploads)

        // One MetaOS has already, e.g. as an avatar, which may be any size, is refused all the same
        val person = people.saveAndFlush(Person(name = "Velikost", email = null))
        mcp.setPersonAvatar(checkNotNull(person.id), ImageSource.DROPBOX, "Artwork", "/S09E75/ogrodje-sizes-thumb1.png", null)
        assertEquals(
            "ogrodje-sizes-thumb1.png is 1280 × 720; an episode's thumbnail B has to be exactly 1920 × 1080",
            refused(EpisodeImage.THUMBNAIL_B, "/S09E75/ogrodje-sizes-thumb1.png"),
        )
        assertEquals(List(5) { null }, mcp.listEpisodeImages("S09E75").map { it.image })

        val cover = assertNotNull(
            mcp.setEpisodeImage("S09E75", EpisodeImage.COVER, ImageSource.DROPBOX, "Artwork", "/S09E75/ogrodje-sizes-podcast.png", null).image
        )
        assertEquals(3000 to 3000, cover.width to cover.height)
        assertEquals(listOf(320, 640, 1280, 1920), cover.sizes.map { it.width })
    }

    @Test
    fun `takes pictures off, and says in the history which ones were set`() {
        val episode = episodes.saveAndFlush(Episode(title = "History", code = "S09E73"))
        dropbox.put("2001", "/S09E73", null)
        dropbox.put("2001", "/S09E73/ogrodje-history-podcast.png", picture(3000, 3000, "png", color = Color(31, 122, 77)))
        mcp.setEpisodeImage("S09E73", EpisodeImage.COVER, ImageSource.DROPBOX, "Artwork", "/S09E73/ogrodje-history-podcast.png", null)

        val removed = mcp.removeEpisodeImage("S09E73", EpisodeImage.COVER)
        assertEquals("ogrodje-history-podcast.png", removed.image?.name)
        assertNull(mcp.removeEpisodeImage("S09E73", EpisodeImage.COVER).image)
        assertNull(episodes.findByCode("S09E73")?.coverImageId)

        val changes = history.of(checkNotNull(episode.id)).flatMap { it.fields }.filter { it.field == "Cover" }
        assertEquals(listOf(FieldChangeDto("Cover", null, "ogrodje-history-podcast.png"), FieldChangeDto("Cover", "ogrodje-history-podcast.png", null)), changes)
    }

    @Test
    fun `gives people avatars`() {
        val person = people.saveAndFlush(Person(name = "Iva Ogrodje", email = null))
        dropbox.put("2001", "/People", null)
        dropbox.put("2001", "/People/iva.jpg", picture(800, 1000, "jpg"))
        val id = checkNotNull(person.id)

        val avatar = mcp.setPersonAvatar(id, ImageSource.DROPBOX, "Artwork", "/People/iva.jpg", null)
        assertTrue(avatar.url.endsWith("/original.jpg"), avatar.url)
        assertEquals(listOf(320, 640), avatar.sizes.map { it.width })
        assertEquals(avatar, mcp.getPersonAvatar(id))
        assertEquals(avatar, mcp.removePersonAvatar(id))
        assertNull(mcp.getPersonAvatar(id))
        assertNull(mcp.removePersonAvatar(id))
    }

    @Test
    fun `shows pictures to anyone who may see the episode, but thumbnails B and C only to the team`() {
        // Aired last, so it's the first released one
        val episode = episodes.saveAndFlush(
            Episode(title = "Released", code = "S09E74").apply { status = EpisodeStatus.PUBLISHED; airedDate = LocalDate.now().plusYears(1) }
        )
        dropbox.put("2001", "/S09E74", null)
        dropbox.put("2001", "/S09E74/thumb1.png", picture(1920, 1080, "png", color = Color(236, 72, 112)))
        dropbox.put("2001", "/S09E74/thumb2.png", picture(1920, 1080, "png", color = Color(20, 20, 20)))
        dropbox.put("2001", "/S09E74/web.png", picture(1920, 1080, "png", color = Color(250, 250, 250)))
        val thumbnail = mcp.setEpisodeImage("S09E74", EpisodeImage.THUMBNAIL, ImageSource.DROPBOX, "Artwork", "/S09E74/thumb1.png", null).image!!
        val b = mcp.setEpisodeImage("S09E74", EpisodeImage.THUMBNAIL_B, ImageSource.DROPBOX, "Artwork", "/S09E74/thumb2.png", null).image!!
        val web = mcp.setEpisodeImage("S09E74", EpisodeImage.WEB, ImageSource.DROPBOX, "Artwork", "/S09E74/web.png", null).image!!
        val person = people.saveAndFlush(Person(name = "Gost", email = null))
        dropbox.put("2001", "/S09E74/gost.png", picture(400, 400, "png"))
        val avatar = mcp.setPersonAvatar(checkNotNull(person.id), ImageSource.DROPBOX, "Artwork", "/S09E74/gost.png", null)
        episodePeople.saveAndFlush(EpisodePerson(episode, person, Role.GUEST))

        val released = "$.data.releasedEpisodes[0]"
        graphql("""{ releasedEpisodes(limit: 1) { code thumbnail { url width height sha256 sizes { width url } } thumbnailB { url } web { url } cover { url } people { name avatar { url } } } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$released.code").value("S09E74"))
            .andExpect(jsonPath("$released.thumbnail.url").value(thumbnail.url))
            .andExpect(jsonPath("$released.thumbnail.width").value(1920))
            .andExpect(jsonPath("$released.thumbnail.sha256").value(thumbnail.sha256))
            .andExpect(jsonPath("$released.thumbnail.sizes[*].width", contains(320, 640, 1280)))
            .andExpect(jsonPath("$released.thumbnailB").value(null as Any?))
            .andExpect(jsonPath("$released.web.url").value(web.url))
            .andExpect(jsonPath("$released.cover").value(null as Any?))
            .andExpect(jsonPath("$released.people[0].name").value("Gost"))
            .andExpect(jsonPath("$released.people[0].avatar.url").value(avatar.url))

        val team = jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", API_READ_SCOPE) }
        graphql("""{ episode(ref: "${episode.id}") { thumbnailB { url name } thumbnailC { url } } person(id: "${person.id}") { avatar { url } } }""", team)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.thumbnailB.url").value(b.url))
            .andExpect(jsonPath("$.data.episode.thumbnailB.name").value("thumb2.png"))
            .andExpect(jsonPath("$.data.episode.thumbnailC").value(null as Any?))
            .andExpect(jsonPath("$.data.person.avatar.url", startsWith("https://media.example.com/images/")))
            .andExpect(jsonPath("$.data.person.avatar.url").value(avatar.url))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        return mvc.perform(request).andExpect(status().isOk)
    }
}
