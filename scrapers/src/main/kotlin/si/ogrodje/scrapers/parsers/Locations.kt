package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import si.ogrodje.scrapers.Web
import tools.jackson.databind.JsonNode
import java.text.Normalizer

/**
 * Where an event is, as the Events page shows it: the venue's [name] (e.g. "Kovačnica", with its hall where the source
 * says) and its [address], street, postcode and city as one line ("Tržaška cesta 2, 1000 Ljubljana"). An online event
 * is [ONLINE], "Online" with no address; either part is null when the source doesn't say.
 */
data class Location(val name: String? = null, val address: String? = null) {
    val isEmpty: Boolean get() = name == null && address == null

    companion object {
        const val ONLINE_NAME = "Online"
        val ONLINE = Location(ONLINE_NAME, null)
        val NONE = Location()
    }
}

/**
 * Locations as the sources write them, made [Location]s alike: from structured data (schema.org's `Place` and
 * `PostalAddress` in JSON-LD, an API's venue: [of], [address], [fromJsonLd]) or from one line of text, e.g. iCal's
 * LOCATION, Google Maps' "Kongresni center Brdo, Predoslje 39, 4000 Kranj, Slovenija" ([parse]).
 *
 * Text is cleaned (HTML, non-breaking spaces, stray commas), Slovenia left out of addresses (every event is in it, but
 * for the few abroad, whose countries stay), the name not repeated in the address, and a city before its own
 * "postcode city" left out ("Velenje, 3320 Velenje"). What says it's online (Zoom, "Na spletu", a link, ...) is
 * [Location.ONLINE]. Nothing is made up: what a source doesn't say stays null.
 */
object Locations {
    private val spaces = Regex("""\s+""")
    private val separators = Regex("""\s*(?:[,;]|\r?\n|\\n|<br\s*/?>)\s*""", RegexOption.IGNORE_CASE)
    private val dashSeparator = Regex("""\s+[–—-]\s+""")
    private val edges = Regex("""^[\s,;:.·|–—-]+|[\s,;:·|–—-]+$""")

    private val countries = setOf("slovenija", "slovenia", "si", "svn", "republika slovenija", "republic of slovenia")

    // "1000 Ljubljana", "SI-1000 Ljubljana", "140 00 Praha", "Beograd 11000"
    private val postcodeCity = Regex("""(?u)^(?:[A-Z]{1,2}-)?(\d{3,5}(?:\s\d{2})?)\s+(\p{L}.*)$""")
    private val cityPostcode = Regex("""(?u)^(\p{L}[\p{L}\s.-]*)\s+(\d{4,5})$""")

    // A street with its number at the end, as streets are written here: "Tržaška cesta 2", "Na Strži 2097/63",
    // "Erzsébet krt. 43-49", "Šaleška cesta 2A", "Tehnološki park 22a"
    private val houseNumber = Regex("""(?u)\p{L}.*\s\d{1,4}(?:[-/]\d{1,4})?\s?[a-zA-Z]?$""")
    // ... or a street's word, numbered or not: "Ameriška ulica", "240 Peachtree St NW", "Am Olympiapark 1"
    private val streetWord = Regex(
        """(?iu)(?<!\p{L})(?:ulica|ul\.|cesta|c\.|trg|nabrežje|ploščad|street|st\.?|road|rd\.|avenue|ave\.?|blvd\.?|""" +
            """straße|strasse|gasse|platz|via|viale|ulice|utca|krt\.)(?!\p{L})""",
    )
    // A room, a hall, a floor: part of the place, not of the address
    private val roomWord = Regex(
        """(?iu)(?<!\p{L})(?:dvoran\p{L}*|predavalnic\p{L}*|sob[aie]|učilnic\p{L}*|sejn\p{L}*|nadstropj\p{L}*|""" +
            """avl[aie]|room|hall|floor|lobby|auditorium|stage)(?!\p{L})""",
    )
    private val online = Regex(
        """(?iu)(?<!\p{L})(?:online|on-line|zoom|ms\s+teams|microsoft\s+teams|teams|webinar\p{L}*|webex|google\s+meet|""" +
            """youtube|livestream|live\s+stream|virtual\p{L}*|virtualn\p{L}*|splet(?:u|a|ni|no|nem)|prenos\s+v\s+živo)(?!\p{L})""",
    )
    private val link = Regex("""(?i)^(?:https?://|www\.)\S+$|(?<!\p{L})(?:zoom\.us|meet\.google\.com|teams\.microsoft\.com)/""")

    /** [text] as plain text: without HTML and its entities, on one line, trimmed of stray separators; null when empty */
    fun clean(text: String?): String? {
        if (text == null) return null
        val plain = if ('<' in text || '&' in text) Jsoup.parse(text).text() else text
        return plain.replace(' ', ' ').replace(' ', ' ').replace(spaces, " ").replace(edges, "").trim().ifEmpty { null }
    }

    /** Whether [text] says the event is online: Zoom, Teams, "Na spletu", "Spletni seminar", a link, ... */
    fun isOnline(text: String?): Boolean {
        val plain = clean(text) ?: return false
        if (link.containsMatchIn(plain)) return true
        return online.containsMatchIn(plain) && parts(plain).none(::isAddressPart)
    }

    /** Whether [part], one part of a location, is a street, or a postcode and a city: where the address starts */
    fun isAddressPart(part: String): Boolean =
        !roomWord.containsMatchIn(part) && (isPostcodeCity(part) || houseNumber.matches(part) || streetWord.containsMatchIn(part))

    private fun isPostcodeCity(part: String) = postcodeCity.matches(part) || cityPostcode.matches(part)

    /** The city of a "1000 Ljubljana", if [part] is one */
    private fun cityOf(part: String): String? =
        postcodeCity.matchEntire(part)?.groupValues?.get(2) ?: cityPostcode.matchEntire(part)?.groupValues?.get(1)

    private fun parts(text: String): List<String> = text.split(separators).mapNotNull(::clean)

    private fun isCountry(part: String) = fold(part) in countries

    /** A case-, space- and diacritics-insensitive form, to compare names and parts of addresses */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * An address of its [parts], e.g. a street, then a postcode and a city ("Tržaška cesta 2", "1000 Ljubljana"), as
     * one line: without empty or repeated parts, Slovenia, or a city that a "postcode city" names
     */
    fun address(vararg parts: String?): String? = join(parts.flatMap { it?.let(::parts).orEmpty() })

    /** A postcode and a city as one part, "1000 Ljubljana"; the city alone when it has its postcode already */
    fun postcodeCity(postcode: String?, city: String?): String? {
        val c = clean(city)
        val p = clean(postcode)
        return when {
            c == null -> p
            p == null || c.any(Char::isDigit) -> c
            else -> "$p $c"
        }
    }

    private fun join(parts: List<String>): String? {
        val kept = parts.filterNot(::isCountry)
        val cities = kept.mapNotNull(::cityOf).map(::fold).toSet()
        return kept.filterIndexed { i, part ->
            val folded = fold(part)
            // Not twice, and not a city its postcode names too ("Velenje, 3320 Velenje")
            kept.subList(0, i).none { fold(it) == folded } && !(cityOf(part) == null && folded in cities)
        }.joinToString(", ").ifEmpty { null }
    }

    /**
     * A location of a venue's [name] and [address] as a source has them apart: cleaned, online when the name says so,
     * and without the name in the address ("Celtra, Park Zvezda, Kongresni trg 3, …" for Celtra is "Kongresni trg 3,
     * …"), which starts at its street. A name that's only a street ("Vilharjeva cesta 40") is the address's start.
     */
    fun of(name: String?, address: String?): Location {
        val n = clean(name)
        val a = address?.let { join(parts(it)) }
        if (n == null && a == null) return Location.NONE
        if (a == null && isOnline(n)) return Location.ONLINE
        if (n == null) return parse(a)
        val nameParts = parts(n).filterNot(::isCountry)
        // A name that's an address: "Vilharjeva cesta 40", or "Trg mladih 9, 1000 Ljubljana"
        if (nameParts.isNotEmpty() && isAddressPart(nameParts.first())) return Location(null, join(nameParts + parts(a.orEmpty())))
        val addressParts = parts(a.orEmpty()).toMutableList()
        // The name, in full or in part, at the address's start
        val folded = nameParts.map(::fold)
        while (addressParts.isNotEmpty() && fold(addressParts.first()) in folded) addressParts.removeAt(0)
        val joined = fold(nameParts.joinToString(" "))
        while (addressParts.size > 1 && joined.startsWith(fold(addressParts.first()))) addressParts.removeAt(0)
        // The address starts at its street: what's before it is the place's other name ("Park Zvezda", the venue in
        // another language), which the venue's name says already
        val street = addressParts.indexOfFirst(::isAddressPart)
        if (street > 0) repeat(street) { addressParts.removeAt(0) }
        val rest = join(addressParts)?.takeIf { fold(it) != fold(n) }
        // "Online and on premise, Fakulteta …": a hybrid event is at its place
        val placeParts = if (rest != null) nameParts.filterNot(::isOnline).ifEmpty { nameParts } else nameParts
        // "Knjižnica Mirana Jarca, Novo mesto" at "Rozmanova ulica 28, 8000 Novo mesto" is the library
        val cities = addressParts.mapNotNull(::cityOf).map(::fold) + addressParts.map(::fold)
        val shortName = if (rest != null && placeParts.size > 1 && fold(placeParts.last()) in cities) placeParts.dropLast(1) else placeParts
        return Location(shortName.joinToString(", ").ifEmpty { null }, rest)
    }

    /**
     * A location written as one line, e.g. iCal's LOCATION: the parts before the first that's a street or a postcode are
     * the venue's name, the rest its address. A room after the address ("…, 1000 Ljubljana, predavalnica P03") goes with
     * the name, as does one in brackets; an address in brackets is one ("Vrt mladinskega centra (Mariborska cesta 2,
     * Celje)"), and a remark in them (six words or more) is left out. Text without an address is a name, "Online" when
     * it says it's online.
     */
    fun parse(text: String?): Location {
        val plain = clean(clean(text)?.replace(remark, "")) ?: return Location.NONE
        if (isOnline(plain)) return Location.ONLINE
        bracketed.matchEntire(plain)?.let { m ->
            val outer = m.groupValues[1]
            val inner = m.groupValues[2]
            if (parts(inner).firstOrNull()?.let(::isAddressPart) == true && parts(outer).none(::isAddressPart)) {
                return of(outer, inner)
            }
        }
        val all = parts(plain).flatMap(::splitDash).filterNot(::isCountry)
        val start = all.indexOfFirst(::isAddressPart)
        if (start < 0) return Location(all.joinToString(", ").ifEmpty { null }, null)
        val name = all.take(start).toMutableList()
        val address = all.drop(start).toMutableList()
        // Rooms and the like after the address are the place's: "…, Ljubljana (Velika sejna soba)", "…, predavalnica P03"
        fun outer(part: String) = bracketed.matchEntire(part)?.groupValues?.get(1) ?: part
        while (address.size > 1 && outer(address.last()).let { roomWord.containsMatchIn(it) || it.first().isLowerCase() }) {
            name.add(start, address.removeAt(address.lastIndex))
        }
        bracketed.matchEntire(address.last())?.takeIf { !isAddressPart(it.groupValues[2]) }?.let {
            address[address.lastIndex] = it.groupValues[1]
            name += it.groupValues[2]
        }
        if (name.isEmpty()) return Location(null, join(address))
        return of(name.joinToString(", "), join(address))
    }

    private val bracketed = Regex("""^(.+?)\s*\(([^()]+)\)$""")

    // A remark in brackets, not a room: "(Udeleženci si ne potrebujejo rezervirati hotela, …)", closed or cut short
    private val remark = Regex("""\s*\((?=(?:[^()\s]+\s+){5})[^()]*(?:\)|$)""")

    /**
     * "MPI Vrelec – Prvomajska ulica 35" is a name and a street, "3250 Rog. Slatina – Sejna soba" a city and a room;
     * "Kovačnica - podjetniški inkubator Kranj", without an address, a name
     */
    private fun splitDash(part: String): List<String> {
        val pieces = part.split(dashSeparator).mapNotNull(::clean)
        return if (pieces.size > 1 && pieces.any(::isAddressPart)) pieces else listOf(part)
    }

    /** A location of a source's place and address that it has as lines, e.g. a venue's, the first maybe its name */
    fun parse(lines: List<String?>): Location = parse(lines.mapNotNull(::clean).joinToString(", "))

    /**
     * The location of a schema.org `Event` ([event]'s `location`): its `Place`, with its name and `PostalAddress` (or
     * its address as text), else online for a `VirtualLocation` or an event whose attendance mode is online. A place
     * with a virtual location too (hybrid) is the place.
     */
    fun fromJsonLd(event: JsonNode?): Location {
        if (event == null) return Location.NONE
        val locations = event.get("location")?.let { if (it.isArray) it.values().toList() else listOf(it) }.orEmpty()
        val place = locations.asSequence().map(::place).firstOrNull { !it.isEmpty && it != Location.ONLINE }
        if (place != null) return place
        val virtual = locations.any { type(it) == "VirtualLocation" || place(it) == Location.ONLINE }
        val mode = event.get("eventAttendanceMode")?.takeIf { it.isString }?.stringValue().orEmpty()
        return if (virtual || mode.endsWith("OnlineEventAttendanceMode")) Location.ONLINE else Location.NONE
    }

    private fun type(node: JsonNode): String? =
        node.get("@type")?.let { if (it.isArray) it.values().firstOrNull() else it }?.takeIf { it.isString }?.stringValue()

    private fun JsonNode.text(name: String): String? = get(name)?.let {
        when {
            it.isString -> it.stringValue()
            it.isObject -> it.get("name")?.takeIf { n -> n.isString }?.stringValue()
            else -> null
        }
    }

    private fun place(node: JsonNode): Location = when {
        node.isString -> parse(node.stringValue())
        type(node) == "VirtualLocation" -> Location.ONLINE
        node.isObject -> {
            val address = node.get("address")
            val line = when {
                address == null || address.isNull -> null
                address.isString -> address.stringValue()
                else -> address(
                    address.text("streetAddress"),
                    postcodeCity(address.text("postalCode"), address.text("addressLocality")),
                    address.text("addressCountry"),
                )
            }
            of(node.text("name"), line)
        }
        else -> Location.NONE
    }

    /** The schema.org `Event`s in [document]'s JSON-LD, also in lists and Yoast's `@graph` */
    fun jsonLdEvents(document: Document): List<JsonNode> =
        document.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { Web.MAPPER.readTree(it.data()) }.getOrNull() }
            .flatMap { json ->
                when {
                    json.isArray -> json.values().asSequence()
                    json.get("@graph")?.isArray == true -> json.get("@graph").values().asSequence()
                    else -> sequenceOf(json)
                }
            }
            .filter { type(it)?.endsWith("Event") == true }
            .toList()
}
