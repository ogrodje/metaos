package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

enum class DeletedKind { EPISODE, PERSON, SESSION, SHOW }

/** Something soft-deleted: an episode by its title (and code), a person, a session by its date and location, a show */
data class DeletedDto(
    val kind: DeletedKind,
    val id: UUID,
    val name: String,
    val code: String?,
    val deletedAt: OffsetDateTime,
    val deletedBy: String?,
)

/** A link restore_link brought back, as it was before it was removed */
data class RestoredLinkDto(val table: String, val id: UUID, val link: Map<String, Any?>)

// The links between episodes, sessions and people, by their tables, with what each links: unlinking deletes them for
// good, and the audit log keeps them as they were
private val LINKS = mapOf(
    "episode_people" to listOf("episode_id" to "episodes", "person_id" to "people"),
    "episode_documents" to listOf("episode_id" to "episodes"),
    "session_documents" to listOf("session_id" to "sessions"),
    "episode_koofr_folders" to listOf("episode_id" to "episodes"),
    "session_koofr_folders" to listOf("session_id" to "sessions"),
    "episode_dropbox_folders" to listOf("episode_id" to "episodes"),
    "session_dropbox_folders" to listOf("session_id" to "sessions"),
)

// What a row of these tables is, as their restore tools have it (restore_episode, ...)
private val LINKED = mapOf("episodes" to "episode", "people" to "person", "sessions" to "session")

/**
 * Bringing back what was deleted. Episodes, people, sessions and shows are soft-deleted, and list_deleted finds them
 * for their restore tools; links are deleted for good, and restore_link brings one back from the audit log.
 */
@Component
class RecoveryMCP(private val jdbcTemplate: JdbcTemplate, private val auditLog: AuditLog) {

    private val toDeleted = RowMapper { rs, _ ->
        DeletedDto(
            kind = DeletedKind.valueOf(rs.getString("kind")),
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            code = rs.getString("code"),
            deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java),
            deletedBy = rs.getString("deleted_by"),
        )
    }

    // Who deleted each is whoever's change last set its deleted_at
    @Transactional(readOnly = true)
    @McpTool(
        name = "list_deleted",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List what has been soft-deleted and can be restored: episodes, people, recording sessions and " +
            "shows, the most recently deleted first, with when and by whom. restore_episode takes an episode's code " +
            "or ID, restore_person and restore_session an ID, restore_show a name"
    )
    fun listDeleted(
        @McpToolParam(description = "Only list those of this kind", required = false) kind: DeletedKind? = null,
    ): List<DeletedDto> = jdbcTemplate.query(
        """
        select d.*,
               (select a.actor from audit_log a
                where a.table_name = d.table_name and a.row_id = d.id
                  and a.old_row ->> 'deleted_at' is null and a.new_row ->> 'deleted_at' is not null
                order by a.id desc limit 1) as deleted_by
        from (select 'EPISODE' as kind, 'episodes' as table_name, id, title as name, cast(code as text) as code, deleted_at
              from episodes where deleted_at is not null
              union all
              select 'PERSON', 'people', id, name, null, deleted_at from people where deleted_at is not null
              union all
              select 'SESSION', 'sessions', id, concat_ws(' · ', to_char(date, 'YYYY-MM-DD'), location), null, deleted_at
              from sessions where deleted_at is not null
              union all
              select 'SHOW', 'shows', id, cast(name as text), null, deleted_at from shows where deleted_at is not null) d
        where cast(? as text) is null or d.kind = ?
        order by d.deleted_at desc
        """.trimIndent(),
        toDeleted, kind?.name, kind?.name,
    )

    // The link comes back as it was, with its ID, so its history goes on; only while what it links is there
    @Transactional
    @McpTool(
        name = "restore_link",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Bring back a link that was removed, as it was: a person's role on an episode, or a Google Doc, " +
            "Koofr folder or Dropbox folder linked to an episode or session. Takes the ID of the change that removed " +
            "it, from get_history or list_recent_changes"
    )
    fun restoreLink(
        @McpToolParam(description = "ID of the change that removed the link") changeId: Long,
    ): RestoredLinkDto {
        val change = requireNotNull(auditLog.find(changeId)) { "There is no change $changeId" }
        val linked = requireNotNull(LINKS[change.table]?.takeIf { change.operation == "DELETE" }) {
            "Change $changeId didn't remove a link"
        }
        linked.forEach { (column, table) ->
            val what = LINKED.getValue(table)
            val there = jdbcTemplate.queryForObject(
                "select count(*) > 0 from $table where id = cast(? as uuid) and deleted_at is null",
                Boolean::class.java, change.old[column].toString(),
            )
            require(there == true) { "The $what it linked is deleted; restore it first (restore_$what)" }
        }
        val removed = jdbcTemplate.queryForObject("select count(*) = 0 from ${change.table} where id = ?", Boolean::class.java, change.rowId)
        require(removed == true) { "That link is back already" }
        try {
            jdbcTemplate.update(
                "insert into ${change.table} select * from jsonb_populate_record(null::${change.table}, (select old_row from audit_log where id = ?))",
                changeId,
            )
        } catch (e: DuplicateKeyException) {
            throw IllegalArgumentException("It has been linked again since", e)
        }
        return RestoredLinkDto(change.table, change.rowId, change.old)
    }
}
