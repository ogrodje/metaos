package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// language: what its episodes are spoken in, e.g. sl-SI
data class ShowDto(val id: UUID?, val name: String, val color: String?, val language: String)

private fun Show.toDto() = ShowDto(id, name, color, language)

private const val LANGUAGE = "What its episodes are spoken in, as a language tag, e.g. sl-SI or en-US"

private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

private fun hexColor(color: String): String =
    color.trim().also { require(HEX_COLOR.matches(it)) { "$color is not a hex color like #0404a4" } }

@Component
class ShowsMCP(
    private val shows: ShowsRepository,
    private val episodes: EpisodesRepository,
) {

    @McpTool(
        name = "list_shows",
        annotations = McpTool.McpAnnotations(
            title = "List shows",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List all shows by name"
    )
    fun list(): List<ShowDto> = shows.findAllByOrderByName().map { it.toDto() }

    @McpTool(
        name = "get_show",
        annotations = McpTool.McpAnnotations(
            title = "Get show",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Find a show by its name (case-insensitive)"
    )
    fun get(@McpToolParam(description = "Show name, e.g. Bytes") name: String): ShowDto? =
        shows.findByName(name)?.toDto()

    @Transactional
    @McpTool(
        name = "create_show",
        annotations = McpTool.McpAnnotations(
            title = "Create show",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Create a new show, its episodes in Slovenian unless it says otherwise"
    )
    fun create(
        @McpToolParam(description = "Unique show name (case-insensitive)") name: String,
        @McpToolParam(description = "Color as hex RGB, e.g. #0404a4", required = false) color: String?,
        @McpToolParam(description = "$LANGUAGE; sl-SI by default", required = false) language: String? = null,
    ): ShowDto {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name must not be blank" }
        require(shows.findByName(trimmed) == null) { "Show $trimmed already exists" }
        val show = Show(name = trimmed, color = color?.let(::hexColor), language = language?.let(::languageTag) ?: DEFAULT_LANGUAGE)
        return shows.save(show).toDto()
    }

    @Transactional
    @McpTool(
        name = "update_show",
        annotations = McpTool.McpAnnotations(
            title = "Update show",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Rename a show, or change its color or the language its episodes are spoken in. Omitted fields " +
            "are left as they are"
    )
    fun update(
        @McpToolParam(description = "Show name (case-insensitive)") name: String,
        @McpToolParam(description = "New unique show name", required = false) newName: String?,
        @McpToolParam(description = "Color as hex RGB, e.g. #0404a4; empty to clear", required = false) color: String?,
        @McpToolParam(description = LANGUAGE, required = false) language: String? = null,
    ): ShowDto {
        val show = requireNotNull(shows.findByName(name)) { "Show $name not found" }
        newName?.trim()?.let { new ->
            require(new.isNotEmpty()) { "Name must not be blank" }
            require(shows.findByName(new).let { it == null || it == show }) { "Show $new already exists" }
            show.name = new
        }
        color?.let { show.color = it.ifBlank { null }?.let(::hexColor) }
        language?.let { show.language = languageTag(it) }
        return show.toDto()
    }

    @Transactional
    @McpTool(
        name = "delete_show",
        annotations = McpTool.McpAnnotations(
            title = "Delete show",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Soft-delete a show. Its episodes are kept and taken out of the show"
    )
    fun delete(@McpToolParam(description = "Show name (case-insensitive)") name: String): ShowDto? =
        shows.findByName(name)?.also { show ->
            episodes.findByShowOrderByCodeDesc(show).forEach { it.show = null }
            shows.delete(show)
        }?.toDto()

    @Transactional
    @McpTool(
        name = "restore_show",
        annotations = McpTool.McpAnnotations(
            title = "Restore show",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Bring back a soft-deleted show by its name (case-insensitive), with the episodes deleting it " +
            "took out of it (unless they have been deleted or moved since). Under a new name, if its own has been " +
            "taken since"
    )
    fun restore(
        @McpToolParam(description = "Show name (case-insensitive)") name: String,
        @McpToolParam(description = "New unique name to restore it under, e.g. when its own has been taken since", required = false)
        newName: String? = null,
    ): ShowDto {
        val id = requireNotNull(shows.findDeletedIdByName(name)) { "There is no deleted show $name" }
        val new = newName?.trim()
        if (new == null) {
            require(shows.findByName(name) == null) { "Show $name already exists; restore it under a new name (newName)" }
        } else {
            require(new.isNotEmpty()) { "Name must not be blank" }
            require(shows.findByName(new) == null) { "Show $new already exists" }
            shows.renameDeleted(id, new)
        }
        shows.restore(id)
        episodes.reattachToShow(id)
        return requireNotNull(shows.findByIdOrNull(id)).toDto()
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_show_episodes",
        annotations = McpTool.McpAnnotations(
            title = "List show episodes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the episodes of a show, latest season and episode first"
    )
    fun listEpisodes(@McpToolParam(description = "Show name (case-insensitive)") name: String): List<EpisodeDto> {
        val show = requireNotNull(shows.findByName(name)) { "Show $name not found" }
        return episodes.findByShowOrderByCodeDesc(show).map { it.toDto() }
    }

    @Transactional
    @McpTool(
        name = "set_episode_show",
        annotations = McpTool.McpAnnotations(
            title = "Set episode show",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Move an episode into a show, or take it out of the show it is in"
    )
    fun setEpisodeShow(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Show name (case-insensitive); omit to take the episode out of its show", required = false) show: String?,
    ): EpisodeDto {
        val episode = requireNotNull(episodes.findByRef(code)) { "Episode $code not found" }
        episode.show = show?.let { requireNotNull(shows.findByName(it)) { "Show $it not found" } }
        return episode.toDto()
    }
}
