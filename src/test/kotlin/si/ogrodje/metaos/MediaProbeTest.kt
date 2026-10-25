package si.ogrodje.metaos

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

/** ffprobe's JSON (-show_format -show_streams) for a file of [streams] in [format], with only what MetaOS reads */
fun ffprobeJson(format: Map<String, Any?>, vararg streams: Map<String, Any?>): String =
    jsonMapper.writeValueAsString(mapOf("format" to format, "streams" to streams.toList()))

/** An hour of 1080p H.264 at 25 fps with AAC, in an MP4, as ffprobe says; [tags] tell files apart */
fun videoJson(width: Int = 1920, height: Int = 1080, duration: Double = 3600.0, tags: Map<String, String> = emptyMap()) = ffprobeJson(
    mapOf("format_name" to "mov,mp4,m4a,3gp,3g2,mj2", "duration" to "$duration", "bit_rate" to "8000000",
        "tags" to mapOf("major_brand" to "isom") + tags),
    mapOf("codec_type" to "video", "codec_name" to "h264", "profile" to "High", "width" to width, "height" to height,
        "avg_frame_rate" to "25/1", "pix_fmt" to "yuv420p", "color_primaries" to "bt709"),
    mapOf("codec_type" to "audio", "codec_name" to "aac", "sample_rate" to "48000", "channels" to 2, "bit_rate" to "192000"),
)

/** An hour of MP3 at 44.1 kHz with a cover, as ffprobe says */
fun audioJson(duration: Double = 3600.0, tags: Map<String, String> = emptyMap()) = ffprobeJson(
    mapOf("format_name" to "mp3", "duration" to "$duration", "bit_rate" to "128000", "tags" to tags),
    mapOf("codec_type" to "audio", "codec_name" to "mp3", "sample_rate" to "44100", "channels" to 2, "bit_rate" to "128000"),
    mapOf("codec_type" to "video", "codec_name" to "mjpeg", "width" to 3000, "height" to 3000, "disposition" to mapOf("attached_pic" to 1)),
)

class MediaProbeTest {

    @Test
    fun `reads a video's container, video and audio`() {
        val json = ffprobeJson(
            mapOf(
                "format_name" to "mov,mp4,m4a,3gp,3g2,mj2", "duration" to "5523.04", "bit_rate" to "8344228",
                "tags" to mapOf("major_brand" to "isom", "creation_time" to "2026-10-20T10:00:00.000000Z", "encoder" to "Lavf61.7.100"),
            ),
            mapOf(
                "codec_type" to "video", "codec_name" to "h264", "profile" to "High", "width" to 1920, "height" to 1080,
                "avg_frame_rate" to "30000/1001", "r_frame_rate" to "30000/1001", "bits_per_raw_sample" to "8",
                "pix_fmt" to "yuv420p", "color_primaries" to "bt709", "disposition" to mapOf("attached_pic" to 0),
            ),
            mapOf("codec_type" to "audio", "codec_name" to "aac", "sample_rate" to "48000", "channels" to 2, "bit_rate" to "127959"),
            mapOf("codec_type" to "audio", "codec_name" to "aac", "sample_rate" to "48000", "channels" to 1),
            mapOf("codec_type" to "data", "codec_name" to "bin_data"),
        )

        val info = parseProbe(jsonMapper, json, "S05E08.mp4").info

        assertEquals(5523.04, info.duration)
        assertEquals(8_344_228L, info.bitrate)
        assertEquals("MP4", info.container)
        assertEquals(listOf("h264", "High"), listOf(info.videoCodec, info.videoProfile))
        assertEquals(1920 to 1080, info.width to info.height)
        assertEquals(29.97, info.frameRate!!, 0.01)
        assertEquals(8, info.bitDepth)
        assertEquals("bt709", info.color)
        assertEquals(listOf("aac", 48000, 2, 127_959L), listOf(info.audioCodec, info.sampleRate, info.channels, info.audioBitrate))
        assertEquals(2, info.audioTracks)
        assertEquals(OffsetDateTime.parse("2026-10-20T10:00:00Z"), info.madeAt)
        assertEquals("Lavf61.7.100", info.encoder)
        assertTrue(info.hasVideo && info.hasAudio)
    }

    @Test
    fun `leaves out an MP3's cover, which isn't video`() {
        val info = parseProbe(jsonMapper, audioJson(), "S05E08.mp3").info

        assertEquals("MP3", info.container)
        assertFalse(info.hasVideo)
        assertNull(info.width)
        assertEquals(listOf("mp3", 44100, 1), listOf(info.audioCodec, info.sampleRate, info.audioTracks))
    }

    @Test
    fun `tells MOVs and M4As from MP4s by their brand, and reads HDR and bit depth`() {
        val prores = ffprobeJson(
            mapOf("format_name" to "mov,mp4,m4a,3gp,3g2,mj2", "tags" to mapOf("major_brand" to "qt  ")),
            mapOf(
                "codec_type" to "video", "codec_name" to "hevc", "profile" to "Main 10", "width" to 3840, "height" to 2160,
                "avg_frame_rate" to "0/0", "r_frame_rate" to "50/1", "pix_fmt" to "yuv420p10le", "color_transfer" to "smpte2084",
                "duration" to "12.5",
            ),
        )
        val video = parseProbe(jsonMapper, prores, "clip.mov").info
        assertEquals("MOV", video.container)
        assertEquals(50.0, video.frameRate)
        assertEquals(10, video.bitDepth)
        assertEquals("HDR (PQ)", video.color)
        // From its streams, as the container doesn't say
        assertEquals(12.5, video.duration)
        assertEquals(0, video.audioTracks)

        val m4a = ffprobeJson(
            mapOf("format_name" to "mov,mp4,m4a,3gp,3g2,mj2", "tags" to mapOf("major_brand" to "M4A ")),
            mapOf("codec_type" to "audio", "codec_name" to "aac", "sample_rate" to "44100", "channels" to 2),
        )
        assertEquals("M4A", parseProbe(jsonMapper, m4a, "audio.m4a").info.container)
    }

    @Test
    fun `refuses what has neither video nor audio`() {
        val json = ffprobeJson(mapOf("format_name" to "mp3"), mapOf("codec_type" to "video", "codec_name" to "png", "disposition" to mapOf("attached_pic" to 1)))
        assertEquals("cover.mp3 has neither video nor audio", assertFailsWith<IllegalArgumentException> { parseProbe(jsonMapper, json, "cover.mp3") }.message)
    }

    // Against ffprobe itself, where FFmpeg is installed, e.g. in devenv: with files it makes, with encoders it has built in
    @Test
    fun `ffprobe reads files, and says why it can't without saying where they are`(@TempDir dir: File) {
        assumeTrue(runs("ffmpeg") && runs("ffprobe"), "FFmpeg isn't installed")
        val video = File(dir, "video.mp4")
        val audio = File(dir, "audio.flac")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "1", "-c:v", "mpeg4", "-c:a", "aac", "-ac", "2", video.path,
        )
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "flac", audio.path)
        val ffprobe = FFprobe("ffprobe", jsonMapper)

        val videoInfo = ffprobe.probe(video.absolutePath, "video.mp4").info
        assertEquals(listOf("mpeg4", 320, 180, 25.0), listOf(videoInfo.videoCodec, videoInfo.width, videoInfo.height, videoInfo.frameRate))
        assertEquals(listOf("aac", 48000, 2), listOf(videoInfo.audioCodec, videoInfo.sampleRate, videoInfo.channels))
        assertEquals(1.0, videoInfo.duration!!, 0.1)

        val audioInfo = ffprobe.probe(audio.absolutePath, "audio.flac").info
        assertEquals(listOf("FLAC", "flac", 44100, false), listOf(audioInfo.container, audioInfo.audioCodec, audioInfo.sampleRate, audioInfo.hasVideo))

        val broken = File(dir, "broken.mp4").apply { writeText("not a video") }
        val refused = assertFailsWith<IllegalArgumentException> { ffprobe.probe(broken.absolutePath, "S05E08.mp4") }
        assertTrue(refused.message!!.startsWith("S05E08.mp4 isn't video or audio that MetaOS can read: "), refused.message)
        assertFalse(dir.path in refused.message!!, refused.message)

        val missing = assertFailsWith<IllegalStateException> { FFprobe("ffprobe-that-isnt-there", jsonMapper).probe(video.absolutePath, "video.mp4") }
        assertEquals("MetaOS can't run ffprobe (ffprobe-that-isnt-there), which reads what's in video and audio", missing.message)
    }

    private fun runs(command: String) = runCatching {
        ProcessBuilder(command, "-version").redirectErrorStream(true).start().run {
            inputStream.readAllBytes()
            waitFor(10, TimeUnit.SECONDS) && exitValue() == 0
        }
    }.getOrDefault(false)

    private fun ffmpeg(vararg arguments: String) {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-y", *arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed: $output" }
    }
}
