package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.util.UUID

data class ColumnChange(val from: Any?, val to: Any?)
data class ChangeDto(
    // restore_link takes it, for a change that removed a link
    val id: Long,
    val at: OffsetDateTime,
    val table: String,
    val rowId: UUID,
    val operation: String,
    val changes: Map<String, ColumnChange>,
    val actor: String?,
    val actorId: String?,
    val client: String?,
    val tool: String?,
)

/** One row of audit_log: a row inserted, updated or deleted, as it was before and after (empty for none) */
data class AuditRow(
    val id: Long,
    val at: OffsetDateTime,
    val table: String,
    val rowId: UUID,
    val operation: String,
    val old: Map<String, Any?>,
    val new: Map<String, Any?>,
    val actor: String?,
    val actorId: String?,
    val client: String?,
    val tool: String?,
)

// Columns that change with every row and say nothing about what happened to it
private val BOOKKEEPING_COLUMNS = setOf("id", "created_at", "updated_at")

/** The columns that differ between a row's old and new values */
fun AuditRow.diff(): Map<String, ColumnChange> =
    (old.keys + new.keys).filter { it !in BOOKKEEPING_COLUMNS && old[it] != new[it] }
        .associateWith { ColumnChange(old[it], new[it]) }

private fun AuditRow.toDto() = ChangeDto(id, at, table, rowId, operation, diff(), actor, actorId, client, tool)

/** The audit trail (V6__audit_log.sql), for get_history and for the front-end's history (History.kt) */
@Component
class AuditLog(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonMapper: JsonMapper,
) {

    private val toRow = RowMapper { rs, _ ->
        fun row(column: String) = rs.getString(column)?.let { jsonMapper.readValue<Map<String, Any?>>(it) }.orEmpty()
        AuditRow(
            id = rs.getLong("id"),
            at = rs.getObject("changed_at", OffsetDateTime::class.java),
            table = rs.getString("table_name"),
            rowId = rs.getObject("row_id", UUID::class.java),
            operation = rs.getString("operation"),
            old = row("old_row"),
            new = row("new_row"),
            actor = rs.getString("actor"),
            actorId = rs.getString("actor_id"),
            client = rs.getString("client"),
            tool = rs.getString("tool"),
        )
    }

    /**
     * Every change to an episode, person, session or show, oldest first, with people's roles on an episode, the
     * documents, Koofr and Dropbox folders linked to an episode or session, the comments in an episode's or session's
     * discussion, an episode's video and audio, the accounts linked to a person, and episodes moving in and out of a
     * session or show
     */
    fun history(id: UUID): List<AuditRow> =
        jdbcTemplate.query(
            """
            select * from audit_log
            where row_id = ?
               or (table_name in ('episode_people', 'episode_documents', 'episode_koofr_folders', 'episode_dropbox_folders', 'episode_media',
                                  'transcripts')
                   and ? in (old_row ->> 'episode_id', new_row ->> 'episode_id', old_row ->> 'person_id', new_row ->> 'person_id'))
               or (table_name in ('session_documents', 'session_koofr_folders', 'session_dropbox_folders')
                   and ? in (old_row ->> 'session_id', new_row ->> 'session_id'))
               or (table_name = 'comments'
                   and ? in (old_row ->> 'episode_id', new_row ->> 'episode_id', old_row ->> 'session_id', new_row ->> 'session_id'))
               or (table_name = 'accounts'
                   and old_row ->> 'person_id' is distinct from new_row ->> 'person_id'
                   and ? in (old_row ->> 'person_id', new_row ->> 'person_id'))
               or (table_name = 'episodes'
                   and ((old_row ->> 'session_id' is distinct from new_row ->> 'session_id'
                         and ? in (old_row ->> 'session_id', new_row ->> 'session_id'))
                     or (old_row ->> 'show_id' is distinct from new_row ->> 'show_id'
                         and ? in (old_row ->> 'show_id', new_row ->> 'show_id'))
                     or (old_row ->> 'recommended_by' is distinct from new_row ->> 'recommended_by'
                         and ? in (old_row ->> 'recommended_by', new_row ->> 'recommended_by'))))
            order by id
            """.trimIndent(),
            toRow, id, id.toString(), id.toString(), id.toString(), id.toString(), id.toString(), id.toString(), id.toString(),
        )

    fun recent(limit: Int): List<AuditRow> =
        jdbcTemplate.query("select * from audit_log order by id desc limit ?", toRow, limit)

    /**
     * The latest changes to episodes, sessions and people and to what's linked to them, newest first: those their
     * histories tell, without sign-ins kept up to date at sign-in, which change no link (AccountsFilter)
     */
    fun recentTold(limit: Int): List<AuditRow> =
        jdbcTemplate.query(
            """
            select * from audit_log
            where table_name in ('episodes', 'sessions', 'people', 'episode_people', 'episode_documents', 'session_documents',
                                 'episode_koofr_folders', 'session_koofr_folders', 'episode_dropbox_folders',
                                 'session_dropbox_folders', 'comments', 'episode_media', 'transcripts')
               or (table_name = 'accounts' and old_row ->> 'person_id' is distinct from new_row ->> 'person_id')
            order by id desc
            limit ?
            """.trimIndent(),
            toRow, limit,
        )

    fun find(id: Long): AuditRow? = jdbcTemplate.query("select * from audit_log where id = ?", toRow, id).singleOrNull()

    /** The MCP tools some rows of a table were inserted with, by row; those inserted another way are left out */
    fun creatingTools(table: String, rowIds: Collection<UUID>): Map<UUID, String> =
        if (rowIds.isEmpty()) emptyMap()
        else jdbcTemplate.query(
            "select row_id, tool from audit_log where table_name = ? and operation = 'INSERT' and tool is not null " +
                "and row_id in (${rowIds.joinToString { "?" }})",
            { rs, _ -> rs.getObject("row_id", UUID::class.java) to rs.getString("tool") },
            table, *rowIds.toTypedArray(),
        ).toMap()
}

@Component
class AuditMCP(private val auditLog: AuditLog) {

    @McpTool(
        name = "get_history",
        annotations = McpTool.McpAnnotations(
            title = "Get history",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List every change to an episode, person, session or show, oldest first: what changed, when, " +
            "by whom, through which MCP client and tool. Includes people's roles on an episode, the documents, Koofr " +
            "and Dropbox folders linked to an episode or session, the comments in an episode's or session's discussion, " +
            "an episode's video and audio, the accounts linked to and unlinked from a person, episodes moving in and out of a session or show and " +
            "the episodes a person is credited with recommending. Soft deletes show up as updates that set deleted_at; " +
            "a removed link comes back with restore_link and the ID of the change that removed it"
    )
    fun history(@McpToolParam(description = "ID of the episode, person, session or show") id: UUID): List<ChangeDto> =
        auditLog.history(id).map { it.toDto() }

    @McpTool(
        name = "list_recent_changes",
        annotations = McpTool.McpAnnotations(
            title = "List recent changes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the latest changes to all data, newest first"
    )
    fun recentChanges(
        @McpToolParam(description = "How many changes to list, 20 by default", required = false) limit: Int?,
    ): List<ChangeDto> = auditLog.recent((limit ?: 20).coerceIn(1, 200)).map { it.toDto() }
}
