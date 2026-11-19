package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "metaos.dropbox.app-key=key", "metaos.dropbox.app-secret=secret", "metaos.dropbox.refresh-token=test",
        "metaos.koofr.email=iva@example.com", "metaos.koofr.app-password=test",
        "metaos.storage.bucket=ogrodje-metaos-test", "metaos.media.bucket=ogrodje-metaos-public-test",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class)
class ImageUploadsTest @Autowired constructor(
    private val dropbox: FakeDropbox,
    private val storage: Storage,
    private val episodes: EpisodesRepository,
    private val dropboxFolders: EpisodeDropboxFoldersRepository,
    private val imagesMCP: ImagesMCP,
    private val people: PeopleRepository,
    private val organisations: OrganisationsRepository,
    private val history: History,
    private val mvc: MockMvc,
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun storageKey(registry: DynamicPropertyRegistry) {
            registry.add("metaos.storage.key", ::fakeServiceAccountKey)
        }
    }

    // Approved users on MetaOS's pages: one who may only read, and one who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    private val bucket get() = storage as FakeStorage

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    // As the browser does: asks where to send a picture, and sends it there
    private fun upload(bytes: ByteArray, contentType: String = "image/png"): String {
        val answer = graphql("""mutation { imageUpload(contentType: "$contentType", size: ${bytes.size}) { id url headers { name value } } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.imageUpload.url", startsWith("https://storage.example.com/fake/uploads/")))
            .andReturn().response.contentAsString
        val id = JsonPath.read<String>(answer, "$.data.imageUpload.id")
        assertEquals(contentType, JsonPath.read<List<String>>(answer, "$.data.imageUpload.headers[?(@.name == 'Content-Type')].value").single())
        bucket.upload("uploads/$id", contentType, bytes.inputStream(), overwrite = false)
        return id
    }

    private fun set(ref: String, kind: EpisodeImage, upload: String, name: String, replace: Boolean = false) = graphql(
        """mutation { setEpisodeImage(ref: "$ref", kind: $kind, upload: "$upload", name: "$name", replace: $replace) {
            kind image { name width height sha256 } dropbox { path copied error } } }"""
    )

    @Test
    fun `makes a picture uploaded from the browser one of the episode's, and lets the upload go`() {
        val episode = episodes.saveAndFlush(Episode(title = "Upload", code = "S09E80"))
        val thumbnail = picture(1920, 1080, "png", color = Color(236, 72, 112))
        val id = upload(thumbnail)

        set("S09E80", EpisodeImage.THUMBNAIL_B, id, "ogrodje-upload-thumb2.png")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setEpisodeImage.kind").value("THUMBNAIL_B"))
            .andExpect(jsonPath("$.data.setEpisodeImage.image.name").value("ogrodje-upload-thumb2.png"))
            .andExpect(jsonPath("$.data.setEpisodeImage.image.width").value(1920))
            .andExpect(jsonPath("$.data.setEpisodeImage.image.sha256").value(sha256(thumbnail)))
            // No Dropbox folder, so nothing in Dropbox
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox").value(null as Any?))

        assertNull(bucket.file("uploads/$id"))
        val image = imagesMCP.listEpisodeImages("S09E80").single { it.kind == EpisodeImage.THUMBNAIL_B }.image
        assertEquals(ImageSource.UPLOAD to "ogrodje-upload-thumb2.png", image?.from to image?.path)
        val changes = history.of(checkNotNull(episode.id)).flatMap { it.fields }.filter { it.field == "Thumbnail B" }
        assertEquals(listOf(FieldChangeDto("Thumbnail B", null, "ogrodje-upload-thumb2.png")), changes)
    }

    @Test
    fun `refuses pictures of another size, and replaces one only when asked`() {
        episodes.saveAndFlush(Episode(title = "Refused", code = "S09E81"))
        val thumbnail = picture(1920, 1080, "png", color = Color(20, 20, 20))
        val other = picture(1920, 1080, "png", color = Color(40, 40, 40))

        // A thumbnail as the cover: refused, and the upload goes all the same
        val wrong = upload(thumbnail)
        set("S09E81", EpisodeImage.COVER, wrong, "ogrodje-refused-thumb1.png")
            .andExpect(jsonPath("$.errors[0].message").value("ogrodje-refused-thumb1.png is 1920 × 1080; an episode's cover has to be exactly 3000 × 3000"))
        assertNull(bucket.file("uploads/$wrong"))
        assertNull(episodes.findByCode("S09E81")?.coverImageId)

        // A GIF may be uploaded, as avatars and logos are, but isn't an episode's picture
        set("S09E81", EpisodeImage.THUMBNAIL, upload(picture(1920, 1080, "gif"), "image/gif"), "thumb1.gif")
            .andExpect(jsonPath("$.errors[0].message").value("thumb1.gif isn't PNG or JPEG, which an episode's thumbnail has to be"))
        graphql("""mutation { imageUpload(contentType: "image/svg+xml", size: 1000) { id } }""")
            .andExpect(jsonPath("$.errors[0].message", startsWith("A picture is PNG, JPEG, WebP or GIF")))
        graphql("""mutation { imageUpload(contentType: "image/png", size: ${MAX_IMAGE_BYTES + 1}) { id } }""")
            .andExpect(jsonPath("$.errors[0].message").value("A picture is up to 50 MB"))
        graphql("""mutation { imageUpload(contentType: "image/png", size: 1000) { id } }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        // As the episode's page asked, before uploads were for any picture
        graphql("""mutation { artworkUpload(ref: "S09E81", contentType: "image/webp", size: 1000) { id } }""")
            .andExpect(jsonPath("$.errors[0].message").value("An episode's pictures are PNG or JPEG"))
        graphql("""mutation { artworkUpload(ref: "S09E81", contentType: "image/png", size: 1000) { id url } }""")
            .andExpect(jsonPath("$.data.artworkUpload.url", startsWith("https://storage.example.com/fake/uploads/")))

        set("S09E81", EpisodeImage.THUMBNAIL, upload(thumbnail), "thumb1.png").andExpect(jsonPath("$.errors").doesNotExist())
        // Another picture where there's one already, unless it's to replace it; the same one again is fine
        set("S09E81", EpisodeImage.THUMBNAIL, upload(other), "thumb1-new.png")
            .andExpect(jsonPath("$.errors[0].message").value("The episode's thumbnail is another picture now; replace it, or keep that one"))
        set("S09E81", EpisodeImage.THUMBNAIL, upload(thumbnail), "thumb1.png").andExpect(jsonPath("$.errors").doesNotExist())
        assertEquals("thumb1.png", imagesMCP.listEpisodeImages("S09E81").first().image?.name)
        set("S09E81", EpisodeImage.THUMBNAIL, upload(other), "thumb1-new.png", replace = true)
            .andExpect(jsonPath("$.errors").doesNotExist())
        assertEquals("thumb1-new.png", imagesMCP.listEpisodeImages("S09E81").first().image?.name)
        assertEquals(emptyList(), bucket.list("uploads", 10))
    }

    @Test
    fun `puts uploads in the episode's Dropbox folder, unless they're there already`() {
        val episode = episodes.saveAndFlush(Episode(title = "Dropbox", code = "S09E82"))
        val thumbnail = picture(1920, 1080, "png", color = Color(250, 250, 250))
        val thumbnailB = picture(1920, 1080, "png", color = Color(0, 128, 255))
        val web = picture(1920, 1080, "png", color = Color(31, 122, 77))
        dropbox.put("2001", "/Season 9", null)
        dropbox.put("2001", "/Season 9/S09E82", null)
        dropbox.put("2001", "/Season 9/S09E82/final", null)
        dropbox.put("2001", "/Season 9/S09E82/final/ogrodje-dropbox-thumb1-v2.png", thumbnail)
        dropbox.put("2001", "/Season 9/S09E82/ogrodje-dropbox-thumb2.png", "an older one".toByteArray())
        val folderId = checkNotNull(dropbox.file("2001", "/Season 9/S09E82")?.id)
        dropboxFolders.saveAndFlush(EpisodeDropboxFolder(episode, "2001", folderId, "/Season 9/S09E82", null))

        // The same file under another name, in a folder in it, is the one
        set("S09E82", EpisodeImage.THUMBNAIL, upload(thumbnail), "ogrodje-dropbox-thumb1.png")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.path").value("/Season 9/S09E82/final/ogrodje-dropbox-thumb1-v2.png"))
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.copied").value(false))
        assertNull(dropbox.file("2001", "/Season 9/S09E82/ogrodje-dropbox-thumb1.png"))

        // Another file of the same name stays, and the new one goes beside it
        set("S09E82", EpisodeImage.THUMBNAIL_B, upload(thumbnailB), "ogrodje-dropbox-thumb2.png")
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.path").value("/Season 9/S09E82/ogrodje-dropbox-thumb2 (1).png"))
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.copied").value(true))
        assertContentEquals("an older one".toByteArray(), dropbox.read("2001", "/Season 9/S09E82/ogrodje-dropbox-thumb2.png", 100))
        assertContentEquals(thumbnailB, dropbox.read("2001", "/Season 9/S09E82/ogrodje-dropbox-thumb2 (1).png", MAX_IMAGE_BYTES))

        // Wherever the folder is now
        dropbox.move("2001", "/Season 9/S09E82", "2001", "/Season 9/S09E82 - dropbox")
        set("S09E82", EpisodeImage.WEB, upload(web), "ogrodje-dropbox-web.png")
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.path").value("/Season 9/S09E82 - dropbox/ogrodje-dropbox-web.png"))
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.copied").value(true))

        // A folder that's gone from its place still lets the picture be the episode's, and says why it isn't in Dropbox
        dropboxFolders.deleteAll(dropboxFolders.findByEpisode(episode))
        dropboxFolders.saveAndFlush(EpisodeDropboxFolder(episode, "2001", "id:gone", "/Season 9/S09E82", null))
        set("S09E82", EpisodeImage.WEB, upload(thumbnailB), "ogrodje-dropbox-web.png", replace = true)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.path").value(null as Any?))
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.error").value("The episode's Dropbox folder isn't in its place anymore"))

        // As does one the bot may only read
        val readOnly = episodes.saveAndFlush(Episode(title = "Read only", code = "S09E83"))
        dropbox.place("2002", "Shared with Iva", writable = false)
        dropbox.put("2002", "/S09E83", null)
        dropboxFolders.saveAndFlush(EpisodeDropboxFolder(readOnly, "2002", checkNotNull(dropbox.file("2002", "/S09E83")?.id), "/S09E83", null))
        set("S09E83", EpisodeImage.THUMBNAIL, upload(web), "ogrodje-read-only-thumb1.png")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setEpisodeImage.dropbox.error").value("The bot may only read the episode's Dropbox folder"))
        assertNull(dropbox.file("2002", "/S09E83/ogrodje-read-only-thumb1.png"))
    }

    @Test
    fun `gives people avatars and organisations logos from uploads, and takes them off`() {
        val person = people.saveAndFlush(Person(name = "Naložena", email = null))
        val organisation = organisations.saveAndFlush(Organisation("Upload d.o.o."))
        val avatar = picture(600, 800, "jpg", color = Color(120, 30, 30))
        val logo = picture(400, 100, "png", seeThrough = true)

        val avatarUpload = upload(avatar, "image/jpeg")
        graphql("""mutation { setPersonAvatar(personId: "${person.id}", upload: "$avatarUpload", name: "naložena.jpg") { name width height sha256 } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setPersonAvatar.name").value("naložena.jpg"))
            .andExpect(jsonPath("$.data.setPersonAvatar.height").value(800))
            .andExpect(jsonPath("$.data.setPersonAvatar.sha256").value(sha256(avatar)))
        assertNull(bucket.file("uploads/$avatarUpload"))
        assertEquals("naložena.jpg", imagesMCP.getPersonAvatar(checkNotNull(person.id))?.name)

        graphql("""mutation { setOrganisationLogo(organisationId: "${organisation.id}", upload: "${upload(logo)}", name: "upload.png") { name width } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setOrganisationLogo.width").value(400))
        assertEquals("upload.png", imagesMCP.getOrganisationLogo(checkNotNull(organisation.id))?.name)
        assertEquals(FieldChangeDto("Logo", null, "upload.png"), history.of(checkNotNull(organisation.id)).flatMap { it.fields }.single { it.field == "Logo" })

        // Only staff who may write
        graphql("""mutation { removePersonAvatar(personId: "${person.id}") { name } }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql("""mutation { removePersonAvatar(personId: "${person.id}") { name } }""")
            .andExpect(jsonPath("$.data.removePersonAvatar.name").value("naložena.jpg"))
        graphql("""mutation { removePersonAvatar(personId: "${person.id}") { name } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.removePersonAvatar").value(null as Any?))
        graphql("""mutation { removeOrganisationLogo(organisationId: "${organisation.id}") { name } }""")
            .andExpect(jsonPath("$.data.removeOrganisationLogo.name").value("upload.png"))
        assertNull(imagesMCP.getPersonAvatar(checkNotNull(person.id)))
        assertNull(imagesMCP.getOrganisationLogo(checkNotNull(organisation.id)))

        // An upload that never arrived
        graphql("""mutation { setPersonAvatar(personId: "${person.id}", upload: "${java.util.UUID.randomUUID()}", name: "gone.png") { name } }""")
            .andExpect(jsonPath("$.errors[0].message").value("gone.png didn't arrive in MetaOS's bucket; upload it again"))
    }

    @Test
    fun `takes an episode's picture off over GraphQL`() {
        episodes.saveAndFlush(Episode(title = "Remove", code = "S09E84"))
        set("S09E84", EpisodeImage.WEB, upload(picture(1920, 1080, "png", color = Color(1, 2, 3))), "remove-web.png")
            .andExpect(jsonPath("$.errors").doesNotExist())
        graphql("""mutation { removeEpisodeImage(ref: "S09E84", kind: WEB) { name } }""")
            .andExpect(jsonPath("$.data.removeEpisodeImage.name").value("remove-web.png"))
        assertNull(episodes.findByCode("S09E84")?.webImageId)
    }

    @Test
    fun `lets agents upload pictures over MCP, sent to the bucket or inline, and puts an episode's in its Dropbox folder`() {
        val episode = episodes.saveAndFlush(Episode(title = "Agent", code = "S09E85"))
        dropbox.put("2001", "/S09E85", null)
        val folderId = checkNotNull(dropbox.file("2001", "/S09E85")?.id)
        dropboxFolders.saveAndFlush(EpisodeDropboxFolder(episode, "2001", folderId, "/S09E85", null))
        val thumbnail = picture(1920, 1080, "png", color = Color(9, 99, 199))

        // Sent to the bucket, as start_image_upload says
        val started = imagesMCP.startImageUpload("image/png", thumbnail.size)
        assertTrue(started.url.startsWith("https://storage.example.com/fake/uploads/"), started.url)
        assertTrue("curl" in started.curl && "\$FILE" in started.curl && "x-goog-resumable" in started.curl.lowercase(), started.curl)
        bucket.upload("uploads/${started.id}", "image/png", thumbnail.inputStream(), overwrite = false)
        val set = imagesMCP.setEpisodeImage("S09E85", EpisodeImage.THUMBNAIL, ImageSource.UPLOAD, null, null, null, "agent-thumb1.png", started.id.toString())
        assertEquals(ImageSource.UPLOAD to "agent-thumb1.png", set.image.from to set.image.path)
        assertEquals(DropboxCopyDto("/S09E85/agent-thumb1.png", copied = true), set.dropbox)
        assertNull(bucket.file("uploads/${started.id}"))

        // Inline, as base64 or a data: URL
        val web = picture(1920, 1080, "jpg", color = Color(200, 10, 10))
        val inline = imagesMCP.setEpisodeImage(
            "S09E85", EpisodeImage.WEB, ImageSource.UPLOAD, null, null, null, "agent-web.jpg",
            data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(web),
        )
        assertEquals(sha256(web), inline.image.sha256)
        assertContentEquals(web, dropbox.read("2001", "/S09E85/agent-web.jpg", MAX_IMAGE_BYTES))
        val person = people.saveAndFlush(Person(name = "Agent", email = null))
        val avatar = imagesMCP.setPersonAvatar(checkNotNull(person.id), ImageSource.UPLOAD, null, null, null, "agent.gif", data = Base64.getMimeEncoder().encodeToString(picture(64, 64, "gif")))
        assertEquals(64 to 64, avatar.width to avatar.height)

        fun refused(name: String? = "x.png", upload: String? = null, data: String? = null) = assertFailsWith<IllegalArgumentException> {
            imagesMCP.setPersonAvatar(checkNotNull(person.id), ImageSource.UPLOAD, null, null, null, name, upload, data)
        }.message.orEmpty()
        assertEquals("An upload needs its file's name (name)", refused(name = null, data = "AAAA"))
        assertEquals("An upload is either upload, from start_image_upload, or data, base64", refused())
        assertEquals("An upload is either upload, from start_image_upload, or data, base64", refused(upload = started.id.toString(), data = "AAAA"))
        assertEquals("The picture's data isn't base64", refused(data = "not base64!"))
        assertTrue(refused(data = Base64.getEncoder().encodeToString(ByteArray(MAX_DATA_BYTES + 1))).startsWith("A picture sent as data is up to 7 MB"))
        assertEquals("x.png isn't a picture MetaOS can read: PNG, JPEG, WebP or GIF", refused(data = Base64.getEncoder().encodeToString("hello".toByteArray())))
        assertEquals("A picture is PNG, JPEG, WebP or GIF: image/png, image/jpeg, image/webp or image/gif",
            assertFailsWith<IllegalArgumentException> { imagesMCP.startImageUpload("image/svg+xml", 100) }.message)
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = writer): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions).andExpect(status().isOk)
    }
}
