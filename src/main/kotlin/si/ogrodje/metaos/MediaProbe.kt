package si.ogrodje.metaos

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What's in a video or audio file, as ffprobe read it: the file as a whole, its video (the first stream that's video,
 * and not a cover a file carries, as MP3s do) and its audio (the first audio stream, and how many there are). Null for
 * what the file doesn't say.
 */
@Embeddable
class MediaInfo(
    // Seconds; bits a second
    val duration: Double?,
    val bitrate: Long?,
    // e.g. MP4, MOV, MP3, WAV
    @Column(columnDefinition = "text") val container: String?,
    // ffprobe's names, e.g. h264, hevc, prores
    @Column(columnDefinition = "text") val videoCodec: String?,
    @Column(columnDefinition = "text") val videoProfile: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val bitDepth: Int?,
    // e.g. bt709, or HDR (PQ)
    @Column(columnDefinition = "text") val color: String?,
    // e.g. aac, mp3, pcm_s24le
    @Column(columnDefinition = "text") val audioCodec: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val audioBitrate: Long?,
    @Column(nullable = false) val audioTracks: Int,
    // When the file says it was made, and with what
    val madeAt: OffsetDateTime?,
    @Column(columnDefinition = "text") val encoder: String?,
) {
    val hasVideo get() = videoCodec != null
    val hasAudio get() = audioTracks > 0
}

/** A file as ffprobe read it: what's in it, and ffprobe's whole answer, as JSON */
class Probed(val info: MediaInfo, val json: String)

/** Reads what's in a file at a URL, e.g. a signed one of MetaOS's bucket, without downloading all of it */
interface MediaProbe {
    /** What's in the file at [url], called [name] where it says what's wrong with it */
    fun probe(url: String, name: String): Probed
}

// How long ffprobe has to read a file, and to wait for each answer from where it is
private val PROBE_TIMEOUT = Duration.ofMinutes(2)
private val READ_TIMEOUT = Duration.ofSeconds(30)

// Only what MetaOS takes, MP4s and MOVs, M4As, MP3s, WAVs and FLACs, so a file that's something else, e.g. a playlist
// naming other URLs, isn't read as that; and from the bucket (https), or a file on disk in tests
private const val FORMATS = "mov,mp3,wav,flac"
private const val PROTOCOLS = "file,https,tls,tcp"

/**
 * ffprobe, from FFmpeg: in MetaOS's image (docker/Dockerfile), and on a development machine from devenv (devenv.nix) or
 * wherever metaos.media.ffprobe says. It reads a file's headers over HTTP range requests, megabytes rather than the file.
 */
@Component
class FFprobe(
    @Value("\${metaos.media.ffprobe:ffprobe}") private val command: String,
    private val jsonMapper: JsonMapper,
) : MediaProbe {

    override fun probe(url: String, name: String): Probed {
        val process = try {
            ProcessBuilder(
                command, "-v", "error", "-rw_timeout", READ_TIMEOUT.toNanos().div(1000).toString(),
                "-format_whitelist", FORMATS, "-protocol_whitelist", PROTOCOLS,
                "-print_format", "json", "-show_format", "-show_streams", url,
            ).start()
        } catch (e: IOException) {
            throw IllegalStateException("MetaOS can't run ffprobe ($command), which reads what's in video and audio", e)
        }
        process.outputStream.close()
        // Both read as they come, so neither fills up and holds ffprobe back
        val output = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().decodeToString() }
        val errors = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().decodeToString() }
        if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ffprobe took over ${PROBE_TIMEOUT.toMinutes()} minutes to read $name")
        }
        // Its errors name the file by its URL, which is signed: by its name instead
        val why = errors.get().lineSequence().map { it.replace(url, name).trim() }.lastOrNull { it.isNotEmpty() }
        require(process.exitValue() == 0) { "$name isn't video or audio that MetaOS can read" + why?.let { ": $it" }.orEmpty() }
        return parseProbe(jsonMapper, output.get(), name)
    }
}

// What MetaOS reads of ffprobe's answer
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FfprobeOutput(val format: FfprobeFormat? = null, val streams: List<FfprobeStream> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FfprobeFormat(
    @param:JsonProperty("format_name") val formatName: String? = null,
    val duration: String? = null,
    @param:JsonProperty("bit_rate") val bitRate: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FfprobeStream(
    @param:JsonProperty("codec_type") val codecType: String? = null,
    @param:JsonProperty("codec_name") val codecName: String? = null,
    val profile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @param:JsonProperty("avg_frame_rate") val avgFrameRate: String? = null,
    @param:JsonProperty("r_frame_rate") val rFrameRate: String? = null,
    @param:JsonProperty("bits_per_raw_sample") val bitsPerRawSample: String? = null,
    @param:JsonProperty("pix_fmt") val pixFmt: String? = null,
    @param:JsonProperty("color_primaries") val colorPrimaries: String? = null,
    @param:JsonProperty("color_transfer") val colorTransfer: String? = null,
    @param:JsonProperty("color_space") val colorSpace: String? = null,
    @param:JsonProperty("sample_rate") val sampleRate: String? = null,
    val channels: Int? = null,
    @param:JsonProperty("bit_rate") val bitRate: String? = null,
    val duration: String? = null,
    val disposition: Map<String, Int> = emptyMap(),
    val tags: Map<String, String> = emptyMap(),
)

/** What ffprobe's JSON (-print_format json -show_format -show_streams) says is in a file called [name] */
fun parseProbe(jsonMapper: JsonMapper, json: String, name: String): Probed {
    val output = runCatching { jsonMapper.readValue<FfprobeOutput>(json) }
        .getOrElse { throw IllegalArgumentException("ffprobe's answer about $name isn't what MetaOS expects", it) }
    // A cover in the file, e.g. an MP3's, is a picture, not its video
    val streams = output.streams.filter { it.disposition["attached_pic"] != 1 }
    val video = streams.firstOrNull { it.codecType == "video" }
    val audio = streams.filter { it.codecType == "audio" }
    require(video != null || audio.isNotEmpty()) { "$name has neither video nor audio" }
    val format = output.format ?: FfprobeFormat()
    val info = MediaInfo(
        duration = format.duration?.toDoubleOrNull() ?: streams.mapNotNull { it.duration?.toDoubleOrNull() }.maxOrNull(),
        bitrate = format.bitRate?.toLongOrNull(),
        container = container(format, name),
        videoCodec = video?.codecName,
        videoProfile = video?.profile,
        width = video?.width,
        height = video?.height,
        frameRate = video?.let { rate(it.avgFrameRate) ?: rate(it.rFrameRate) },
        bitDepth = video?.let { it.bitsPerRawSample?.toIntOrNull() ?: bitDepth(it.pixFmt) },
        color = video?.let(::color),
        audioCodec = audio.firstOrNull()?.codecName,
        sampleRate = audio.firstOrNull()?.sampleRate?.toIntOrNull(),
        channels = audio.firstOrNull()?.channels,
        audioBitrate = audio.firstOrNull()?.bitRate?.toLongOrNull(),
        audioTracks = audio.size,
        madeAt = (format.tags["creation_time"] ?: video?.tags?.get("creation_time"))
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
        encoder = format.tags["encoder"]?.takeIf { it.isNotBlank() } ?: video?.tags?.get("encoder")?.takeIf { it.isNotBlank() },
    )
    return Probed(info, json)
}

// "25/1", "30000/1001"; "0/0" for none
private fun rate(fraction: String?): Double? {
    val (numerator, denominator) = fraction?.split('/')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 } ?: return null
    return if (numerator > 0 && denominator > 0) numerator / denominator else null
}

// By the pixel format, e.g. yuv420p10le, when the stream doesn't say
private fun bitDepth(pixFmt: String?): Int? = when {
    pixFmt == null -> null
    Regex("p(10|12|16)(le|be)?$").containsMatchIn(pixFmt) -> Regex("p(10|12|16)").find(pixFmt)!!.groupValues[1].toInt()
    else -> 8
}

private fun color(video: FfprobeStream): String? = when (video.colorTransfer) {
    "smpte2084" -> "HDR (PQ)"
    "arib-std-b67" -> "HDR (HLG)"
    else -> listOf(video.colorPrimaries, video.colorSpace).firstOrNull { it != null && it != "unknown" }
}

// ffprobe names MP4s, MOVs and M4As alike (mov,mp4,m4a,...): told apart by the brand they say they are, else by the name
private fun container(format: FfprobeFormat, name: String): String? {
    val first = format.formatName?.substringBefore(',') ?: return null
    if (first != "mov") return if (first == "matroska") "MKV" else first.uppercase()
    val brand = format.tags["major_brand"]?.trim()?.lowercase()
    return when {
        brand == "qt" -> "MOV"
        brand == "m4a" -> "M4A"
        brand != null -> "MP4"
        else -> name.substringAfterLast('.', "").uppercase().ifEmpty { "MP4" }
    }
}
