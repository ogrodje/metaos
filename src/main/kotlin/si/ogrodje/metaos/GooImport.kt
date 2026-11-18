package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** What importing from Goo did: organisations made and changed, and events added */
data class GooImportSummary(val created: Int, val updated: Int, val events: Int, val logos: Int, val venues: Int = 0)

/**
 * The one-time move of Goo (goo.ogrodje.si, github.com/ogrodje/goo) into MetaOS (OG-64), which can be run again until
 * Goo is gone: Goo's meetups from Hygraph (`metaos.hygraph.endpoint`, HYGRAPH_ENDPOINT), where they're edited, become
 * organisations, and the events Goo lists now (its public timeline, `metaos.goo.url`) MetaOS's, by the same IDs, so the
 * scrapers find them again rather than add them twice. Not its old events: what's past stays Goo's.
 *
 * A meetup is the organisation it was imported as before (`goo_id`), else the one of its name, else of its website,
 * else a new one; of one that's there, only what it hasn't got is filled in, so nothing the team set is changed. A new
 * one Goo hid, or Hygraph hasn't published, is archived. Its logo is published from Hygraph where it has none.
 */
@Component
class GooImport(
    @Value("\${metaos.hygraph.endpoint:}") private val hygraph: String,
    @Value("\${metaos.goo.url}") private val goo: String,
    private val organisations: OrganisationsRepository,
    private val events: EventsRepository,
    private val eventsOf: Events,
    private val images: ObjectProvider<Images>,
    private val transaction: TransactionTemplate,
    private val mapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    val available: Boolean get() = hygraph.isNotBlank()

    fun import(): GooImportSummary {
        check(available) { "MetaOS has no Hygraph to import from (HYGRAPH_ENDPOINT)" }
        val meetups = meetups()
        var created = 0
        var updated = 0
        var logos = 0
        val byGooId = mutableMapOf<String, Organisation>()
        meetups.forEach { meetup ->
            transaction.executeWithoutResult {
                val (organisation, isNew) = match(meetup)
                val changed = fill(organisation, meetup)
                if (isNew) created++ else if (changed) updated++
                organisations.save(organisation)
                byGooId[meetup.id] = organisation
            }
        }
        // Logos last, each on its own, as a picture that can't be read doesn't stop the rest
        meetups.filter { it.logo != null }.forEach { meetup ->
            val organisation = byGooId[meetup.id] ?: return@forEach
            if (organisation.logoImageId != null) return@forEach
            val images = images.ifAvailable ?: return@forEach
            runCatching {
                val image = images.publish(ImageSource.URL, null, checkNotNull(meetup.logo), "${meetup.name} logo")
                transaction.executeWithoutResult {
                    organisations.findById(checkNotNull(organisation.id)).ifPresent { it.logoImageId = image.id }
                }
                logos++
            }.onFailure { log.warn("No logo for {} from {}: {}", meetup.name, meetup.logo, it.message) }
        }
        val added = timeline(byGooId)
        // Where each one's events usually are, as Hygraph has no venues, for its events that don't say
        val venues = byGooId.values.count { o ->
            transaction.execute { organisations.findById(checkNotNull(o.id)).map(eventsOf::fillVenue).orElse(false) } == true
        }
        log.info("Imported Goo: {} organisations made, {} changed, {} events, {} logos, {} venues", created, updated, added, logos, venues)
        return GooImportSummary(created, updated, added, logos, venues)
    }

    private fun match(meetup: Meetup): Pair<Organisation, Boolean> {
        organisations.findAll().firstOrNull { it.gooId == meetup.id }?.let { return it to false }
        organisations.findByName(meetup.name.trim())?.takeIf { it.gooId == null }?.let { return it to false }
        meetup.homepage?.let(::site)?.let { site ->
            organisations.findAll().firstOrNull { it.gooId == null && it.websiteUrl?.let(::site) == site }?.let { return it to false }
        }
        return Organisation(meetup.name.trim()).apply {
            collectsEvents = true
            if (meetup.hidden || meetup.stage != "PUBLISHED") archivedAt = OffsetDateTime.now()
        } to true
    }

    // What it hasn't got, from the meetup: true when something changed
    private fun fill(o: Organisation, m: Meetup): Boolean {
        var changed = false
        fun <T> set(current: T?, value: T?, apply: (T) -> Unit) {
            if (current == null && value != null) {
                apply(value)
                changed = true
            }
        }
        // One that was there before it was Goo's, e.g. a partner, gathers its events now, as Goo did, unless Goo hid it
        if (o.gooId == null && !m.hidden && m.stage == "PUBLISHED" && !o.collectsEvents) {
            o.collectsEvents = true
            changed = true
        }
        set(o.gooId, m.id) { o.gooId = it }
        set(o.websiteUrl, m.homepage?.takeIf { it.matches(HTTP) }) { o.websiteUrl = it }
        set(o.linkedinUrl, m.linkedin?.takeIf { it.startsWith("https://") }) { o.linkedinUrl = it }
        set(o.facebookUrl, m.facebook?.takeIf { it.startsWith("https://") }) { o.facebookUrl = it }
        set(o.instagramUrl, m.instagram?.takeIf { it.startsWith("https://") }) { o.instagramUrl = it }
        set(o.meetupUrl, m.meetup?.takeIf { it.matches(HTTP) }) { o.meetupUrl = it }
        set(o.eventbriteUrl, m.eventbrite?.takeIf { it.matches(HTTP) }) { o.eventbriteUrl = it }
        set(o.icalUrl, m.ical?.takeIf { it.matches(CALENDAR) }) { o.icalUrl = it }
        set(o.lumaUrl, m.luma?.takeIf { it.matches(HTTP) }) { o.lumaUrl = it }
        set(o.discordUrl, m.discord?.takeIf { it.matches(HTTP) }) { o.discordUrl = it }
        set(o.kompotUrl, m.kompot?.takeIf { it.matches(HTTP) }) { o.kompotUrl = it }
        set(o.mainColor, m.mainColor?.let(::colour)) { o.mainColor = it }
        set(o.backgroundColor, m.backgroundColor?.let(::colour)) { o.backgroundColor = it }
        return changed
    }

    /** Goo's events that haven't ended, by the organisations their meetups are, the ones MetaOS hasn't got */
    private fun timeline(byGooId: Map<String, Organisation>): Int {
        val now = Instant.now()
        var added = 0
        get(URI.create(goo.trimEnd('/') + "/timeline")).forEach { node ->
            fun text(name: String) = node[name]?.takeUnless { it.isNull }?.asString()?.trim()?.ifEmpty { null }
            val organisation = text("meetupID")?.let(byGooId::get) ?: return@forEach
            val id = text("id") ?: return@forEach
            val title = text("title") ?: return@forEach
            val start = text("startDateTime")?.let(Instant::parse) ?: return@forEach
            val end = text("endDateTime")?.let(Instant::parse)
            if ((end ?: start) < now || text("hiddenAt") != null) return@forEach
            transaction.executeWithoutResult {
                if (events.findByOrganisationAndExternalIdIn(organisation, listOf(id)).isNotEmpty()) return@executeWithoutResult
                val source = SOURCES[text("source")] ?: MANUAL_SOURCE
                events.save(
                    Event(organisations.getReferenceById(checkNotNull(organisation.id)), source, id, title, start.atOffset(java.time.ZoneOffset.UTC)).apply {
                        endsAt = end?.takeIf { it >= start }?.atOffset(java.time.ZoneOffset.UTC)
                        hasStartTime = node["hasStartTime"]?.asBoolean() ?: true
                        hasEndTime = node["hasEndTime"]?.asBoolean() ?: true
                        description = text("description")
                        url = text("eventURL")?.takeIf { it.matches(HTTP) }
                        sourceUrl = text("sourceURL")
                        locationName = text("locationName")
                        locationAddress = text("locationAddress")
                        promotedAt = text("promotedAt")?.let(OffsetDateTime::parse)
                    }
                )
                added++
            }
        }
        return added
    }

    private data class Meetup(
        val id: String, val name: String, val stage: String?, val hidden: Boolean,
        val homepage: String?, val meetup: String?, val eventbrite: String?, val discord: String?, val linkedin: String?,
        val facebook: String?, val instagram: String?, val kompot: String?, val ical: String?, val luma: String?,
        val logo: String?, val mainColor: String?, val backgroundColor: String?,
    )

    private fun meetups(): List<Meetup> {
        val query = """
            query { meetups(first: 1000) {
              id name stage hidden homePageUrl meetupUrl eventbriteUrl discordUrl linkedInUrl facebookUrl instagramUrl
              kompotUrl icalUrl lumaUrl mainColor { hex } backgroundColor { hex } logoImage { url }
            } }
        """.trimIndent()
        val body = mapper.writeValueAsString(mapOf("query" to query))
        val request = HttpRequest.newBuilder(URI.create(hygraph)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Hygraph answered ${response.statusCode()}: ${response.body().take(200)}" }
        val root = mapper.readTree(response.body())
        val meetups = root["data"]?.get("meetups")
        check(meetups != null && meetups.isArray) { "Hygraph didn't list the meetups: ${root["errors"] ?: response.body().take(200)}" }
        return (0 until meetups.size()).map { meetups[it] }.map { m ->
            fun text(node: JsonNode?, name: String) = node?.get(name)?.takeUnless { it.isNull }?.asString()?.trim()?.ifEmpty { null }
            Meetup(
                id = checkNotNull(text(m, "id")), name = checkNotNull(text(m, "name")), stage = text(m, "stage"),
                hidden = m["hidden"]?.asBoolean() ?: false,
                homepage = text(m, "homePageUrl"), meetup = text(m, "meetupUrl"), eventbrite = text(m, "eventbriteUrl"),
                discord = text(m, "discordUrl"), linkedin = text(m, "linkedInUrl"), facebook = text(m, "facebookUrl"),
                instagram = text(m, "instagramUrl"), kompot = text(m, "kompotUrl"), ical = text(m, "icalUrl"),
                luma = text(m, "lumaUrl"), logo = text(m["logoImage"], "url"),
                mainColor = text(m["mainColor"], "hex"), backgroundColor = text(m["backgroundColor"], "hex"),
            )
        }
    }

    private fun get(url: URI): List<JsonNode> {
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Goo answered ${response.statusCode()}" }
        val list = mapper.readTree(response.body())
        check(list.isArray) { "Goo's timeline isn't a list of events" }
        return (0 until list.size()).map { list[it] }
    }

    companion object {
        private val HTTP = Regex("^https?://.+")
        private val CALENDAR = Regex("^(https?|webcal)://.+")

        // A colour as #rrggbb; Hygraph's hex may have its alpha too
        fun colour(hex: String): String? = Regex("^#?([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$").find(hex.trim())?.let { "#" + it.groupValues[1].lowercase() }

        fun site(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()

        /** Goo's sources as the scrapers name them (scrapers/Model.kt); FERI had no parser */
        val SOURCES = mapOf(
            "Meetup" to "MEETUP", "Eventbrite" to "EVENTBRITE", "TehnoloskiParkLjubljana" to "TEHNOLOSKI_PARK_LJUBLJANA",
            "PrimorskiTehnoloskiPark" to "PRIMORSKI_TEHNOLOSKI_PARK", "GZS" to "GZS", "StartupSi" to "STARTUP_SI",
            "ICal" to "ICAL", "Manual" to MANUAL_SOURCE, "FRI" to "FRI", "KCUI" to "KCUI",
            "InkubatorSavinjskeRegije" to "INKUBATOR_SAVINJSKE_REGIJE", "RacunalniskiMuzej" to "RACUNALNISKI_MUZEJ",
            "LUI" to "LUI", "SpletneUrice" to "SPLETNE_URICE", "SLAIF" to "SLAIF", "Luma" to "LUMA",
            "DataScience" to "DATA_SCIENCE", "PomurskiTehnoloskiPark" to "POMURSKI_TEHNOLOSKI_PARK",
            "InkubatorBelaKrajina" to "INKUBATOR_BELA_KRAJINA", "InkubatorRogaskaSlatina" to "INKUBATOR_ROGASKA_SLATINA",
            "Katapult" to "KATAPULT", "PodjetniskiInkubatorPodbreznik" to "PODJETNISKI_INKUBATOR_PODBREZNIK",
            "MPIKKoroska" to "MPIK_KOROSKA",
        )
    }
}
