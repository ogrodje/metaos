package si.ogrodje.scrapers

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The scrapers of Slovenia's tech, developer and startup events, ported from Goo (github.com/ogrodje/goo): a
 * Temporal worker of their own, apart from MetaOS, on task queue [SCRAPERS_QUEUE], with a browser for the sites that
 * need one. MetaOS's events workflow asks them for one organisation's URL at a time and keeps what they find.
 */
@SpringBootApplication
class Scrapers

fun main(args: Array<String>) {
    runApplication<Scrapers>(*args)
}
