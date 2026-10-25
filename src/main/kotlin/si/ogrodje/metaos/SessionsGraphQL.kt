package si.ogrodje.metaos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

// Creating and changing recording sessions over GraphQL, e.g. renaming one on its page, as create_session and
// update_session do over MCP (Sessions.kt)
@Controller
class SessionsGraphQL(private val sessions: Sessions) {

    @MutationMapping
    @PreAuthorize(WRITES)
    fun createSession(
        @Argument date: String,
        @Argument name: String?,
        @Argument location: String?,
        @Argument address: String?,
    ): Session = sessions.create(day(date), name, location, address)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateSession(
        @Argument id: UUID,
        @Argument date: String?,
        @Argument name: String?,
        @Argument location: String?,
        @Argument address: String?,
    ): Session = sessions.update(id, date?.let(::day), name, location, address)

    private fun day(date: String): LocalDate =
        try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("A session's date is a day, e.g. 2026-10-22, not $date")
        }
}
