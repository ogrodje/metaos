package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A post's audio and video to download, from Patreon's web API's answer for it, as it gives them to the creator */
class DownloadsTest {
    private val mapper = JsonMapper.builder().build()
    private val web = PatreonWeb(
        Browser(PatreonProperties(), mapper),
        PatreonApi(PatreonProperties(), TokenStore(PatreonProperties(), mapper), mapper, java.time.Clock.systemUTC()),
        Media(PatreonProperties()),
        mapper,
    )

    // A Mux token: only its claims are read, not its signature
    private val muxToken = "eyJhbGciOiJSUzI1NiJ9." +
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"x","exp":1793066400,"aud":"v"}""".toByteArray()) + ".signature"

    @Test
    fun `an audio post's MP3, as it was uploaded, not its preview`() {
        val downloads = web.downloads(mapper.readTree(
            """{"data":{"id":"172886590","type":"post","attributes":{"title":"S05E08 Poletna vročica AVDIO","post_file":{"duration":5837,"url":"https://c10.patreonusercontent.com/x/1.mp3"}}},
            "included":[
              {"type":"media","id":"767881200","attributes":{"download_url":"https://c10.patreonusercontent.com/4/patreon-media/p/post/172886590/a/b/1.mp3?token-hash=h&token-time=1793145600","file_name":"S05E08 - Poletna vročica.mp3","media_type":"audio","owner_relationship":"audio","size_bytes":233475840,"state":"ready"}},
              {"type":"media","id":"767881557","attributes":{"download_url":"https://c10.patreonusercontent.com/4/patreon-media/p/media/767881200/a/b/1.mp3?token-time=1793145600","file_name":"/tmp/tmp_l9d5hva.mp3","media_type":"audio","owner_relationship":"audio_preview","size_bytes":null,"state":"ready"}}
            ]}""",
        ))
        val audio = downloads.single()
        assertEquals(MediaKind.AUDIO, audio.kind)
        assertEquals("S05E08 - Poletna vročica.mp3", audio.fileName)
        assertEquals(233475840L, audio.sizeBytes)
        assertEquals(5837L, audio.durationSeconds)
        assertEquals(OffsetDateTime.parse("2026-10-28T00:00:00Z"), audio.expiresAt)
        assertNull(audio.referer)
    }

    @Test
    fun `a video post's MP4, named after the post, with Patreon as its referer`() {
        val downloads = web.downloads(mapper.readTree(
            """{"data":{"id":"172923221","type":"post","attributes":{"title":"S05E08 Poletna vročica: VIDEO","post_file":{"duration":5836.833333}}},
            "included":[
              {"type":"media","id":"768117302","attributes":{"download_url":"https://stream.mux.com/abc/highest.mp4?token=$muxToken","file_name":null,"media_type":"video","owner_relationship":"main","size_bytes":null,"state":"ready"}},
              {"type":"media","id":"771315326","attributes":{"download_url":"https://c10.patreonusercontent.com/x/1.mp4?token-time=1793145600","file_name":"preview.mp4","media_type":"video","owner_relationship":"vertical_video_preview","size_bytes":1748970,"state":"ready"}},
              {"type":"media","id":"771315561","attributes":{"download_url":"https://c10.patreonusercontent.com/x/1.png","media_type":"image","owner_relationship":"clip_start_screen","state":"ready"}},
              {"type":"media","id":"771315320","attributes":{"download_url":"https://stream.mux.com/def/highest.mp4?token=$muxToken","media_type":"video","owner_relationship":"video_preview","state":"ready"}}
            ]}""",
        ))
        val video = downloads.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals("S05E08 Poletna vročica- VIDEO.mp4", video.fileName)
        assertNull(video.sizeBytes)
        assertEquals(5837L, video.durationSeconds)
        assertEquals(OffsetDateTime.parse("2026-10-27T02:00:00Z"), video.expiresAt)
        assertEquals("https://www.patreon.com/", video.referer)
    }

    @Test
    fun `a video the gateway put on a post, named as it was uploaded, as an MP4`() {
        val video = web.downloads(mapper.readTree(
            """{"data":{"id":"1","type":"post","attributes":{"title":"Test","post_file":null}},
            "included":[{"type":"media","id":"2","attributes":{"download_url":"https://stream.mux.com/abc/highest.mp4?token=$muxToken","file_name":"010-animated.mov","media_type":"video","owner_relationship":"video","state":"ready"}}]}""",
        )).single()
        assertEquals("010-animated.mp4", video.fileName)
        assertNull(video.durationSeconds)
    }

    @Test
    fun `a post without audio or video has nothing to download`() {
        assertEquals(emptyList(), web.downloads(mapper.readTree("""{"data":{"id":"1","type":"post","attributes":{"title":"Text"}}}""")))
    }
}
