package si.ogrodje.metaos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

// Changing a recording session from MetaOS's pages, e.g. its name, as update_session does over MCP (Sessions.kt)
@Controller
class SessionsGraphQL(private val sessions: Sessions) {

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateSession(
        @Argument id: UUID,
        @Argument date: String?,
        @Argument name: String?,
        @Argument location: String?,
        @Argument address: String?,
    ): Session {
        val day = date?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("A session's date is a day, e.g. 2026-10-22, not $it")
            }
        }
        return sessions.update(id, day, name, location, address)
    }
}
