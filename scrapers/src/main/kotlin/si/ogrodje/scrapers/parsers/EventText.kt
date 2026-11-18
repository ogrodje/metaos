package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * When and where an event is, read from the text of a post that announces it, in Slovenian, as Goo's EventText.
 *
 * Posts on incubators' WordPress sites (LUI, Pomurski tehnološki park, Bela krajina, Rogaška Slatina) have no event
 * fields; the post's date is when it was published. They write the event's date, time and place in many ways:
 *  - `KDAJ: 27. oktober 2026, med 13:00 – 16:00 KJE: LUI – …, Vojkova cesta 63, Ljubljana`
 *  - `Datum: torek, 13. oktober 2026` / `Čas: 14.00–17.00` / `Lokacija: LUI`
 *  - `Kdaj: Ponedeljek, 31. 8. ob 9.00 (do 12.00)` (no year), `KDAJ? 21.10.26 ob 16.00`
 *  - `Termin: Torek, 26. 5. 2026, 9:00 – 11:00`
 *  - `KDAJ: petek, 25. september in sobota, 26. september 2026` (two days)
 *  - `📅 KDAJ: petek, 20. november 2026, ob 17.00 – 19.00.` / `📍 LOKACIJA: Podjetniški inkubator Bela krajina`
 *  - `📅 Datum: 19. november 2026 ⏰ Čas: 16.00 – 19.00 📍 Kraj: Pomurski tehnološki park, Plese 9a`
 *
 * and, without any marker, in a sentence (read only with `sentences`, for sites that write so):
 *  - `…, ki bo dne 27.8.2026 s pričetkom ob 9.30 uri v Pomurskem tehnološkem parku.`
 *  - `…, ki bo v četrtek, 26. novembra 2026, ob 12. uri, …`
 */
object EventText {

    /** What a post's text says about when and where */
    data class Details(
        val startDate: LocalDate,
        val startTime: LocalTime?,
        val endTime: LocalTime?,
        val endDate: LocalDate?,
        val location: String?,
    )

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "maj" to 5, "jun" to 6,
        "jul" to 7, "avg" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "dec" to 12,
    )

    // A line that says when: "KDAJ:", "Kdaj in kje:", "Datum:", "Termin:", "KDAJ?", or one starting with 📅/🗓
    private val whenMarker = Regex("""(?iu)^\W*(?:(?:kdaj|datum|termin)\b[^:?]{0,12}:|kdaj\s*\?|📅|🗓)""")
    private val whereMarker = Regex("""(?iu)(?:\b(?:kje|lokacija)\s*:|^\W*kje\s*\?|📍)\s*""")
    // "📍 Kraj: …", "📍 LOKACIJA: …": the label after the pin
    private val whereLabel = Regex("""(?iu)^(?:kje|lokacija|kraj|naslov)\s*:\s*""")
    private val stopMarker = Regex("""(?iu)\b(?:kje|lokacija|kraj|prijav\w*|udeležba|cena)\s*:|📍""")
    // A sentence about registering, not about the event: "Prijave zbiramo do 23. novembra", "najkasneje do 8.6.2026"
    private val deadline = Regex("""(?iu)\b(?:prijav\w*|najkasneje|rok\b|do\s+(?:\p{L}+,?\s+)?\d)""")

    // "13. november 2026", "25. september", "17. junija 2026", "03. 09. 2026", "31. 8.", "16.5.2026"
    private val date =
        Regex("""(?iu)\b(\d{1,2})\.\s*(?:(\d{1,2})\.|(jan|feb|mar|apr|maj|jun|jul|avg|sep|okt|nov|dec)[\p{L}]*)(?:\s*,?\s*(\d{4}))?""")
    private val year = Regex("""\b(20\d\d)\b""")

    // "9:00 – 10:30", "ob 9.00 (do 12.00)", "od 12.00 do 24.00", "14.00–17.00", "(10.00-12.15)"; never "16.5.2026"
    private val timeRange =
        Regex("""(?iu)(\d{1,2})[:.](\d{2})(?!\d|\.\d)(?:\s*(?:uri?|h)?[\s(]*(?:–|—|-|do|in)\s*(\d{1,2})[:.](\d{2})(?!\d|\.\d))?""")
    // "ob 12. uri", "ob 8 h": a full hour
    private val hourOnly = Regex("""(?iu)\bob\s+(\d{1,2})\.?\s*(?:uri|h)\b""")

    private val lineBreaks = Regex("""(?i)<br\s*/?>|</(?:p|li|h\d|div)>""")
    private val spaces = Regex("""\s+""")

    /** The post's text as lines, split where the HTML breaks lines or paragraphs */
    fun linesOf(html: String): List<String> =
        html.replace(lineBreaks, "\n").split('\n')
            .map { Jsoup.parse(it).text().replace(' ', ' ').replace(spaces, " ").trim() }
            .filter { it.isNotEmpty() }

    internal fun clock(h: String, m: String): LocalTime? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        return when {
            hh == 24 && mm == 0 -> LocalTime.of(23, 59)
            hh < 24 && mm < 60 -> LocalTime.of(hh, mm)
            else -> null
        }
    }

    private data class Found(val date: LocalDate, val start: Int, val end: Int)

    /**
     * Dates in [text], in order. A missing year is the one written later in the text, else the publish year; a date
     * that would be two months before publishing is taken to be in the next year (posts announce, they don't recap:
     * one post says "3. februar 2025" for February 2026).
     */
    private fun datesIn(text: String, published: LocalDate): List<Found> =
        date.findAll(text).mapNotNull { m ->
            val month = m.groups[2]?.value?.toIntOrNull()
                ?: m.groups[3]?.value?.let { months[it.lowercase().take(3)] }
                ?: return@mapNotNull null
            val yearNum = m.groups[4]?.value?.toIntOrNull()
                ?: year.find(text.substring(m.range.last + 1))?.groupValues?.get(1)?.toInt()
                ?: published.year
            val day = try {
                LocalDate.of(yearNum, month, m.groupValues[1].toInt())
            } catch (_: DateTimeException) {
                return@mapNotNull null
            }
            val fixed = if (day.isBefore(published.minusDays(60))) day.plusYears(1) else day
            Found(fixed, m.range.first, m.range.last + 1)
        }.toList()

    /** The start and end time after the first date: between it and a second date, else anywhere after it */
    private fun timesIn(window: String, found: List<Found>): Pair<LocalTime?, LocalTime?> {
        val first = found.firstOrNull() ?: return null to null
        val segment = window.substring(first.end, found.getOrNull(1)?.start ?: window.length)
        val rest = window.substring(first.end)
        val m = timeRange.find(segment) ?: timeRange.find(rest)
        if (m != null) {
            val start = clock(m.groupValues[1], m.groupValues[2])
            val end = m.groups[3]?.value?.let { clock(it, m.groupValues[4]) }?.takeIf { start != null }
            return start to end
        }
        val hour = hourOnly.find(segment) ?: hourOnly.find(rest)
        return hour?.let { clock(it.groupValues[1], "0") } to null
    }

    private fun detailsIn(window: String, published: LocalDate, lines: List<String>): Details? {
        val found = datesIn(window, published)
        val first = found.firstOrNull() ?: return null
        val endDate = found.drop(1).map { it.date }.find { it.isAfter(first.date) && !it.isAfter(first.date.plusDays(14)) }
        val (start, end) = timesIn(window, found)
        return Details(first.date, start, end, endDate, locationIn(lines))
    }

    /**
     * When and where, from the lines of a post; null when it names no date. The first marked line ("KDAJ:", "📅", …)
     * that names a date wins. With [sentences], a post without one is read from its first sentence that names both a
     * date and a time and isn't about registering.
     */
    fun details(lines: List<String>, published: LocalDate, sentences: Boolean = false): Details? {
        val marked = lines.indices.asSequence()
            .mapNotNull { at ->
                whenMarker.find(lines[at])?.let { marker ->
                    val raw = (listOf(lines[at].substring(marker.range.last + 1)) + lines.subList(at + 1, minOf(at + 3, lines.size)))
                        .joinToString(" ").trim()
                    cutAtStop(raw)
                }
            }
            .mapNotNull { detailsIn(it, published, lines) }
            .firstOrNull()
        if (marked != null || !sentences) return marked
        return lines.asSequence()
            .filter { deadline.find(it) == null }
            .mapNotNull { line -> detailsIn(line, published, lines)?.takeIf { it.startTime != null } }
            .firstOrNull()
    }

    private fun cutAtStop(text: String): String =
        stopMarker.find(text)?.takeIf { it.range.first > 0 }?.let { text.substring(0, it.range.first) } ?: text

    /**
     * The place after "KJE:"/"Lokacija:"/📍 (without a "Kraj:" label after the pin), or on the next line when the
     * marker stands alone, as the post writes it. Per-day directions ("Petek, 16. oktober 2026 – zbirno mesto …") are
     * not a place.
     */
    fun locationIn(lines: List<String>): String? =
        lines.indices.asSequence()
            .mapNotNull { i ->
                whereMarker.find(lines[i])?.let { m ->
                    val sameLine = cut(whereLabel.replaceFirst(lines[i].substring(m.range.last + 1), ""))
                    sameLine.ifEmpty { lines.getOrNull(i + 1)?.let { cut(whereLabel.replaceFirst(it, "")) } ?: "" }
                }
            }
            .find { it.isNotEmpty() && whenMarker.find(it) == null && !startsWithDate(it) }
            ?.removeSuffix(".")?.trim()?.take(200)

    private fun cut(text: String): String = cutAtStop(text).trim()

    private val weekdayThenDate = Regex("""(?iu)^(?:[\p{L}]+,\s*)?\d{1,2}\.\s*""")
    private fun startsWithDate(text: String) = weekdayThenDate.find(text) != null

    private val filler = setOf("in", "na", "za", "pri", "v", "z", "s", "o", "od", "do", "the", "and", "of", "for", "to")

    private fun words(title: String): Set<String> =
        Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in filler }
            .toSet()

    /**
     * Two titles of the same event: at least half the words of the shorter one are in the other, e.g. "SPREMEMBA
     * DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?" and "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?". Two shared
     * words at least (one-word titles: that word): "Pitch trening" is not "Kako pripraviti dober pitch".
     */
    fun similarTitles(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        val shared = (x intersect y).size
        val shorter = minOf(x.size, y.size)
        return shared >= minOf(2, shorter) && shared > 0 && shared * 2 >= shorter
    }
}
