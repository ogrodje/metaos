package si.ogrodje.metaos

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Where an event is, for the Events page's filter (V39__regions.sql, OG-71): Slovenia's 12 statistical regions, as
 * they are since 2015 and in Wikipedia's order, by their Slovenian names ([label]); online; or abroad
 */
enum class Region(val label: String) {
    POMURSKA("Pomurska"),
    PODRAVSKA("Podravska"),
    KOROSKA("Koroška"),
    SAVINJSKA("Savinjska"),
    ZASAVSKA("Zasavska"),
    POSAVSKA("Posavska"),
    JUGOVZHODNA("Jugovzhodna Slovenija"),
    OSREDNJESLOVENSKA("Osrednjeslovenska"),
    GORENJSKA("Gorenjska"),
    PRIMORSKO_NOTRANJSKA("Primorsko-notranjska"),
    GORISKA("Goriška"),
    OBALNO_KRASKA("Obalno-kraška"),
    ONLINE("Na spletu"),
    ABROAD("Tujina");

    companion object {
        /** One by its key or its name, whatever its case, as the tools take it: OSREDNJESLOVENSKA, Osrednjeslovenska */
        fun parse(text: String): Region {
            val wanted = fold(text.trim()).replace(Regex("[\\s_-]+"), "_")
            return requireNotNull(entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) || fold(it.label).replace(Regex("[\\s_-]+"), "_") == wanted }) {
                "$text isn't a region: one of ${entries.joinToString { it.name }}"
            }
        }
    }
}

/** What the tools say a region is */
const val REGION = "Its region, one of Slovenia's 12 statistical regions (POMURSKA, PODRAVSKA, KOROSKA, SAVINJSKA, ZASAVSKA, " +
    "POSAVSKA, JUGOVZHODNA, OSREDNJESLOVENSKA, GORENJSKA, PRIMORSKO_NOTRANJSKA, GORISKA, OBALNO_KRASKA), ONLINE or ABROAD"

/**
 * Tells a place's region from its name and address, with Postgres's region_of (V39__regions.sql), which has
 * Slovenia's postcodes and places: online for a name that's online, abroad, then a postcode's, a place's name's, a
 * region's name's; none when nothing in them says.
 */
@Component
class Regions(private val jdbcTemplate: JdbcTemplate) {

    fun of(name: String?, address: String?): Region? {
        if (name.isNullOrBlank() && address.isNullOrBlank()) return null
        return jdbcTemplate.queryForObject("select region_of(?, ?)", String::class.java, name, address)?.let(Region::valueOf)
    }
}
