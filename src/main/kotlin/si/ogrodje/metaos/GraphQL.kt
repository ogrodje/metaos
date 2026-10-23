package si.ogrodje.metaos

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import graphql.schema.DataFetchingEnvironment
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import java.net.URLEncoder
import java.text.Collator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/*
 * GraphQL at /graphql (resources/graphql/schema.graphqls), for the front-end. It's open to anyone, as some of it is
 * public; the bearer token, when there is one, says who is asking.
 */

const val MAX_RELEASED_EPISODES = 20

// An approved user's token with metaos:read, and one with metaos:write, for mutations (Security.kt)
const val SIGNED_IN = "@approval.reads(authentication)"
const val WRITES = "@approval.writes(authentication)"

// When an episode is or will be recorded: its own date, or else its session's
private val byRecordingDate = compareBy<Episode, LocalDate?>(nullsLast()) { it.recordingDate ?: it.session?.date }
    .thenBy(nullsLast()) { it.code }

@Controller
class EpisodesGraphQL(private val episodes: EpisodesRepository, private val episodePeople: EpisodePeopleRepository) {

    @QueryMapping
    fun releasedEpisodes(@Argument limit: Int): List<Episode> {
        require(limit in 1..MAX_RELEASED_EPISODES) { "limit must be between 1 and $MAX_RELEASED_EPISODES" }
        return episodes.findLatestAiredByStatus(EpisodeStatus.PUBLISHED, PageRequest.of(0, limit))
    }

    // An idea without a session or a date isn't a recording yet
    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun upcomingRecordings(): List<Episode> =
        episodes.findByStatusIn(listOf(EpisodeStatus.IDEA, EpisodeStatus.PLANNED, EpisodeStatus.SCHEDULED))
            .filter { it.session != null || it.recordingDate != null }
            .sortedWith(byRecordingDate)

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun inProduction(): List<Episode> =
        episodes.findByStatusIn(
            listOf(EpisodeStatus.RECORDED, EpisodeStatus.IN_PRODUCTION, EpisodeStatus.READY, EpisodeStatus.EARLY_ACCESS)
        ).sortedWith(byRecordingDate)

    // What's released is public; the rest is for signed-in users, but for the ideas, which are listed on their own
    @QueryMapping("episodes")
    fun allEpisodes(): List<Episode> =
        if (signedIn()) episodes.findLatestFirstByStatusNot(EpisodeStatus.IDEA)
        else episodes.findLatestFirstByStatus(EpisodeStatus.PUBLISHED)

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun ideas(): List<Episode> = episodes.findByStatusOrderByAddedDateDescTitleAsc(EpisodeStatus.IDEA)

    @SchemaMapping(typeName = "Episode")
    fun show(episode: Episode): String? = episode.show?.name

    @SchemaMapping(typeName = "Episode")
    fun showColor(episode: Episode): String? = episode.show?.color

    // The people of all the episodes in an answer, in one query. Candidates, who aren't guests yet, are the team's.
    @BatchMapping(typeName = "Episode")
    fun people(episodes: List<Episode>): Map<Episode, List<EpisodePersonDto>> {
        val signedIn = signedIn()
        val byEpisode = episodePeople.findByEpisodesWithPerson(episodes)
            .filter { signedIn || it.role != Role.CANDIDATE }
            .groupBy { it.episode }
        return episodes.associateWith { byEpisode[it].orEmpty().toEpisodePersonDtos() }
    }
}

/** A Google Doc linked to an episode or a session (Documents.kt); linking takes only Google Docs */
data class DocumentLinkDto(val fileId: String) {
    val url = "https://docs.google.com/document/d/$fileId/edit"
}

// Whether the caller may see what isn't public - what @PreAuthorize(SIGNED_IN) checks, for fields others get empty
fun signedIn() = SecurityContextHolder.getContext().authentication.approvedFor(API_READ_SCOPE)

// Docs are the team's, so others get none
@Controller
class DocumentsGraphQL(
    private val episodeDocuments: EpisodeDocumentsRepository,
    private val sessionDocuments: SessionDocumentsRepository,
) {

    @BatchMapping(typeName = "Episode", field = "documents")
    fun episodeDocuments(episodes: List<Episode>): Map<Episode, List<DocumentLinkDto>> {
        val byEpisode = if (signedIn()) episodeDocuments.findByEpisodeIds(episodes.mapNotNull { it.id }).groupBy { it.episode } else emptyMap()
        return episodes.associateWith { episode -> byEpisode[episode].orEmpty().map { DocumentLinkDto(it.fileId) } }
    }

    @BatchMapping(typeName = "Session", field = "documents")
    fun sessionDocuments(sessions: List<Session>): Map<Session, List<DocumentLinkDto>> {
        val bySession = if (signedIn()) sessionDocuments.findBySessionIds(sessions.mapNotNull { it.id }).groupBy { it.session } else emptyMap()
        return sessions.associateWith { session -> bySession[session].orEmpty().map { DocumentLinkDto(it.fileId) } }
    }
}

// A query parameter as Koofr's web app and MetaOS read it: spaces as %20, as a + stays a +
private fun queryValue(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

// A folder in Koofr's web app, where a shared place has the same ID for all its users
private fun koofrUrl(place: String, path: String) = "https://app.koofr.net/app/storage/$place?path=${queryValue(path)}"

/** A Koofr folder linked to an episode or session: a place shared with the bot, named when Koofr answers, and a path */
data class KoofrFolderLinkDto(val place: String, val path: String) {
    val url = koofrUrl(place, path)
}

/** A photo in a linked Koofr folder: its thumbnail from MetaOS (KoofrPhotos), and the folder it's in, in Koofr */
data class KoofrPhotoDto(val place: String, val path: String, val name: String) {
    val thumbnail = "/koofr/thumbnail?place=${queryValue(place)}&path=${queryValue(path)}"
    val url = koofrUrl(place, path.substringBeforeLast('/').ifEmpty { "/" })
}

// Everything about one episode, session or person, for its page. What isn't public is empty, or null, for anyone else,
// as an episode, session or person can also be reached through the released episodes.
@Controller
class DetailsGraphQL(
    private val episodeRepository: EpisodesRepository,
    private val sessionRepository: SessionsRepository,
    private val peopleRepository: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val history: History,
) {

    // A released episode's page is anyone's, as the Episodes page lists it; any other is the team's, and anyone else gets
    // none, as for a code that isn't there, so it doesn't say that there is one
    @QueryMapping
    fun episode(@Argument ref: String): Episode? =
        episodeRepository.findByRef(ref)?.takeIf { signedIn() || it.status == EpisodeStatus.PUBLISHED }

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun session(@Argument id: UUID): Session? = sessionRepository.findByIdOrNull(id)

    // So is the page of someone on a released episode, as the People page lists them; anyone else's is the team's
    @QueryMapping
    fun person(@Argument id: UUID): Person? =
        peopleRepository.findByIdOrNull(id)?.takeIf { person ->
            signedIn() || episodePeople.findByPersonWithEpisode(person).any { it.isPublic() }
        }

    @SchemaMapping(typeName = "Episode")
    fun machineSummary(episode: Episode): String? = episode.machineSummary.takeIf { signedIn() }

    // The titles the title is A/B tested against are the team's, until one of them wins
    @SchemaMapping(typeName = "Episode")
    fun titleB(episode: Episode): String? = episode.titleB.takeIf { signedIn() }

    @SchemaMapping(typeName = "Episode")
    fun titleC(episode: Episode): String? = episode.titleC.takeIf { signedIn() }

    // Who recommended an episode is the team's too
    @SchemaMapping(typeName = "Episode")
    fun recommendedBy(episode: Episode): Person? = episode.recommendedBy.takeIf { signedIn() }

    @SchemaMapping(typeName = "Episode", field = "history")
    fun episodeHistory(episode: Episode): List<HistoryEntryDto> =
        if (signedIn()) history.of(checkNotNull(episode.id)) else emptyList()

    // As who recommended an episode is
    @SchemaMapping(typeName = "Person")
    fun recommended(person: Person): List<Episode> =
        if (signedIn()) episodeRepository.findLatestFirstByRecommendedBy(person) else emptyList()

    @SchemaMapping(typeName = "Session")
    fun address(session: Session): String? = session.address.takeIf { signedIn() }

    @SchemaMapping(typeName = "Session", field = "episodes")
    fun sessionEpisodes(session: Session): List<Episode> =
        if (signedIn()) episodeRepository.findBySessionOrderByCodeDesc(session).sortedWith(compareBy(nullsLast()) { it.code })
        else emptyList()

    @SchemaMapping(typeName = "Session", field = "history")
    fun sessionHistory(session: Session): List<HistoryEntryDto> =
        if (signedIn()) history.of(checkNotNull(session.id)) else emptyList()

    // What the team did lately, as the episodes', sessions' and people's histories tell it, e.g. for the home page
    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun recentChanges(@Argument limit: Int): List<HistoryEntryDto> = history.recent(limit.coerceIn(1, MAX_RECENT_CHANGES))
}

// The most changes recentChanges answers with
private const val MAX_RECENT_CHANGES = 200

// Koofr folders are the team's too, so others get none. Lists of episodes have them as well, so they're batched, and
// Koofr itself, slower than the database, is only asked for what a query wants: place names and photos.
@Controller
class KoofrGraphQL(
    private val episodeFolders: EpisodeKoofrFoldersRepository,
    private val sessionFolders: SessionKoofrFoldersRepository,
    private val koofr: ObjectProvider<Koofr>,
    private val photos: ObjectProvider<KoofrPhotos>,
) {

    @BatchMapping(typeName = "Episode", field = "koofrFolders")
    fun episodeKoofrFolders(episodes: List<Episode>): Map<Episode, List<KoofrFolderLinkDto>> {
        val byEpisode = if (signedIn()) episodeFolders.findByEpisodeIds(episodes.mapNotNull { it.id }).groupBy { it.episode } else emptyMap()
        return episodes.associateWith { episode -> byEpisode[episode].orEmpty().map { KoofrFolderLinkDto(it.place, it.path) } }
    }

    @BatchMapping(typeName = "Session", field = "koofrFolders")
    fun sessionKoofrFolders(sessions: List<Session>): Map<Session, List<KoofrFolderLinkDto>> {
        val bySession = if (signedIn()) sessionFolders.findBySessionIds(sessions.mapNotNull { it.id }).groupBy { it.session } else emptyMap()
        return sessions.associateWith { session -> bySession[session].orEmpty().map { KoofrFolderLinkDto(it.place, it.path) } }
    }

    // Places by name, with one call for all the folders, when Koofr is on and answers; a page doesn't fail for want of a name
    @BatchMapping(typeName = "KoofrFolder", field = "placeName")
    fun placeNames(folders: List<KoofrFolderLinkDto>): Map<KoofrFolderLinkDto, String?> {
        val names = runCatching { koofr.ifAvailable?.places()?.associate { it.id to it.name } }.getOrNull().orEmpty()
        return folders.associateWith { names[it.place] }
    }

    // All of them without a limit. None when Koofr is off or doesn't answer: a page doesn't fail for want of photos.
    @SchemaMapping(typeName = "KoofrFolder", field = "photos")
    fun photos(folder: KoofrFolderLinkDto, @Argument limit: Int?): List<KoofrPhotoDto> {
        require(limit == null || limit > 0) { "limit must be at least 1" }
        val photos = photos.ifAvailable?.takeIf { signedIn() } ?: return emptyList()
        return runCatching { photos.of(folder.place, folder.path, limit ?: Int.MAX_VALUE) }.getOrDefault(emptyList())
            .map { KoofrPhotoDto(it.place, it.path, it.name) }
    }
}

// A folder by its path in Dropbox's web app, for one without a link for members yet. Paths there are in the viewer's own
// Dropbox, so it's right only for those who have its shared folder where the bot has it. Its home, without the place.
private fun dropboxUrl(place: DropboxPlace?, path: String): String {
    if (place == null) return "https://www.dropbox.com/home"
    val inDropbox = ((place.path ?: "/${place.name}") + path).trimEnd('/')
    return "https://www.dropbox.com/home" + inDropbox.split('/').joinToString("/") { queryValue(it) }
}

/**
 * A Dropbox folder linked to an episode or session: where it is now, found by its ID, or where it was linked when
 * Dropbox doesn't answer; missing once it's gone from its shared folder. Its link for members, which opens it for all
 * of them, or else its path in Dropbox's web app.
 */
data class DropboxFolderLinkDto(val place: String, val placeName: String?, val path: String, val missing: Boolean, val url: String)

/**
 * A picture in a linked Dropbox folder: its thumbnail from MetaOS (DropboxPictures), whose path changes with the
 * picture, so one replaced under the same name isn't shown from the cache; and its folder's [url]
 */
data class DropboxPictureDto(val place: String, val path: String, val name: String, val modified: OffsetDateTime?, val url: String) {
    val thumbnail = "/dropbox/thumbnail?place=${queryValue(place)}&path=${queryValue(path)}" +
        modified?.let { "&v=${it.toEpochSecond()}" }.orEmpty()
}

// Dropbox folders are the team's too, so others get none. Lists of episodes have them as well, so they're batched, and
// found by their IDs in DropboxFolderIndex, which asks Dropbox once per shared folder and minute. Dropbox is only asked
// for pictures when a query wants them.
@Controller
class DropboxGraphQL(
    private val episodeFolders: EpisodeDropboxFoldersRepository,
    private val sessionFolders: SessionDropboxFoldersRepository,
    private val index: ObjectProvider<DropboxFolderIndex>,
    private val pictures: ObjectProvider<DropboxPictures>,
) {

    @BatchMapping(typeName = "Episode", field = "dropboxFolders")
    fun episodeDropboxFolders(episodes: List<Episode>): Map<Episode, List<DropboxFolderLinkDto>> {
        val byEpisode = if (signedIn()) episodeFolders.findByEpisodeIds(episodes.mapNotNull { it.id }).groupBy { it.episode } else emptyMap()
        val found = Found(byEpisode.values.flatten().map { it.place })
        return episodes.associateWith { episode ->
            byEpisode[episode].orEmpty().map { found.link(it.place, it.folderId, it.path, it.url) }.sortedBy { it.path }
        }
    }

    @BatchMapping(typeName = "Session", field = "dropboxFolders")
    fun sessionDropboxFolders(sessions: List<Session>): Map<Session, List<DropboxFolderLinkDto>> {
        val bySession = if (signedIn()) sessionFolders.findBySessionIds(sessions.mapNotNull { it.id }).groupBy { it.session } else emptyMap()
        val found = Found(bySession.values.flatten().map { it.place })
        return sessions.associateWith { session ->
            bySession[session].orEmpty().map { found.link(it.place, it.folderId, it.path, it.url) }.sortedBy { it.path }
        }
    }

    // All of them without a limit. None when Dropbox is off or doesn't answer, or the folder is missing: a page doesn't
    // fail for want of pictures.
    @SchemaMapping(typeName = "DropboxFolder", field = "pictures")
    fun pictures(folder: DropboxFolderLinkDto, @Argument limit: Int?): List<DropboxPictureDto> {
        require(limit == null || limit > 0) { "limit must be at least 1" }
        val pictures = pictures.ifAvailable?.takeIf { signedIn() && !folder.missing } ?: return emptyList()
        return runCatching { pictures.of(folder.place, folder.path, limit ?: Int.MAX_VALUE) }.getOrDefault(emptyList())
            .map { DropboxPictureDto(it.place, it.path, it.name, it.modified, folder.url) }
    }

    // What Dropbox says about the places of some links; nothing when it's off or doesn't answer, as a page doesn't fail
    // for want of where a folder is now
    private inner class Found(places: Collection<String>) {
        private val index = this@DropboxGraphQL.index.ifAvailable?.takeIf { places.isNotEmpty() }
        private val shared = index?.let { runCatching { it.places() }.getOrNull() }?.associateBy { it.id }
        private val folders = places.toSet().filter { shared?.containsKey(it) == true }
            .associateWith { place -> runCatching { index!!.folders(place) }.getOrNull() }

        fun link(place: String, folderId: String, linkedPath: String, url: String?): DropboxFolderLinkDto {
            val inPlace = folders[place]
            val path = inPlace?.get(folderId) ?: linkedPath
            // Missing when Dropbox says the place isn't shared with the bot anymore, or the folder isn't in it
            val missing = shared != null && (place !in shared || inPlace != null && folderId !in inPlace)
            return DropboxFolderLinkDto(place, shared?.get(place)?.name, path, missing, url ?: dropboxUrl(shared?.get(place), path))
        }
    }
}

@Controller
class CurrentUserGraphQL(private val people: PeopleRepository, private val accounts: Accounts) {

    @QueryMapping
    fun me(): CurrentUserDto? = currentJwt()?.toCurrentUserDto(accounts)

    // The person and the account as the rest of the API has them, so their episodes resolve as everyone else's do
    @SchemaMapping(typeName = "CurrentUser")
    fun person(user: CurrentUserDto): Person? = user.person?.id?.let(people::findByIdOrNull)

    @SchemaMapping(typeName = "CurrentUser")
    fun account(user: CurrentUserDto): Account? = accounts.signedInAccount()
}

/** An episode a person is on, with all of their roles in it */
data class EpisodeRolesDto(val episode: Episode, val roles: List<Role>)

// Leading academic titles, which names are sorted past
private val TITLE = Regex("^dr\\.\\s+", RegexOption.IGNORE_CASE)

// By name as Slovenian sorts it (Č after C, ...), and dr. Matevž Pesek with the Ms
val peopleByName: Comparator<Person> =
    Collator.getInstance(Locale.forLanguageTag("sl")).let { collator -> compareBy(collator) { it.name.replace(TITLE, "") } }

// What anyone may see of who's on an episode: anything but a candidate, on a released one
private fun EpisodePerson.isPublic() = episode.status == EpisodeStatus.PUBLISHED && role != Role.CANDIDATE

// Everyone, for the People page. Who's on the released episodes, and as what, is public, with their bios and links;
// those only on episodes still being made, or on none yet, and candidates, are the team's.
@Controller
class PeopleGraphQL(private val people: PeopleRepository, private val episodePeople: EpisodePeopleRepository) {

    @QueryMapping("people")
    fun allPeople(): List<Person> {
        val everyone = people.findAll()
        val listed = if (signedIn() || everyone.isEmpty()) everyone
        else episodePeople.findByPeopleWithEpisode(everyone).filter { it.isPublic() }.map { it.person }.distinct()
        return listed.sortedWith(peopleByName)
    }

    // The episodes of all the people in an answer, in one query, the latest first
    @BatchMapping(typeName = "Person", field = "episodes")
    fun episodes(people: List<Person>): Map<Person, List<EpisodeRolesDto>> {
        val signedIn = signedIn()
        val byPerson = episodePeople.findByPeopleWithEpisode(people)
            .filter { signedIn || it.isPublic() }
            .groupBy { it.person }
        return people.associateWith { person ->
            byPerson[person].orEmpty().groupBy { it.episode }
                .map { (episode, assignments) -> EpisodeRolesDto(episode, assignments.map { it.role }.sorted()) }
        }
    }
}

// Pictures are anyone's who may see the episode or person, but for the thumbnails that go with the alternative titles,
// which are the team's, as the titles are. Lists of episodes and people have them as well, so they're batched.
@Controller
class ImagesGraphQL(
    private val imagesRepository: ImagesRepository,
    private val images: ObjectProvider<Images>,
    private val people: PeopleRepository,
) {

    @BatchMapping(typeName = "Episode", field = "thumbnail")
    fun thumbnails(episodes: List<Episode>) = of(episodes) { it.thumbnailImageId }

    @BatchMapping(typeName = "Episode", field = "thumbnailB")
    fun thumbnailsB(episodes: List<Episode>) = if (signedIn()) of(episodes) { it.thumbnailBImageId } else emptyMap()

    @BatchMapping(typeName = "Episode", field = "thumbnailC")
    fun thumbnailsC(episodes: List<Episode>) = if (signedIn()) of(episodes) { it.thumbnailCImageId } else emptyMap()

    @BatchMapping(typeName = "Episode", field = "cover")
    fun covers(episodes: List<Episode>) = of(episodes) { it.coverImageId }

    @BatchMapping(typeName = "Person", field = "avatar")
    fun avatars(people: List<Person>) = of(people) { it.avatarImageId }

    // Who's on an episode, with their pictures, e.g. on its page; whoever may see them there may see their avatars
    @BatchMapping(typeName = "EpisodePerson", field = "avatar")
    fun episodePeopleAvatars(onEpisodes: List<EpisodePersonDto>): Map<EpisodePersonDto, ImageDto> {
        val avatars = people.findAllById(onEpisodes.mapNotNull { it.personId }.toSet()).associate { it.id to it.avatarImageId }
        return of(onEpisodes) { avatars[it.personId] }
    }

    // None while the public bucket is off; those without one are left out, and so null
    private fun <T : Any> of(items: List<T>, imageId: (T) -> UUID?): Map<T, ImageDto> {
        val images = images.ifAvailable ?: return emptyMap()
        val byId = imagesRepository.findAllById(items.mapNotNull(imageId).toSet()).associateBy { it.id }
        return items.mapNotNull { item -> imageId(item)?.let(byId::get)?.let { item to images.toDto(it) } }.toMap()
    }
}

// Search is the team's, as most of what it finds is: ideas, candidates, sessions
@Controller
class SearchGraphQL(private val search: Search) {

    @QueryMapping("search")
    @PreAuthorize(SIGNED_IN)
    fun find(@Argument text: String, @Argument limit: Int): SearchResults = search.search(text, limit)
}

/** require(...) failing, e.g. on an argument out of range, is the caller's mistake: its message goes back to them */
@Component
class BadRequestExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        (ex as? IllegalArgumentException)?.let {
            GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST).message(it.message.orEmpty()).build()
        }
}

/*
 * Anyone may query /graphql, and one short query can ask for a lot: the same list under a thousand aliases, or, signed
 * in, people, their episodes, who recommended those and their episodes again, as deep as it likes. The front-end's
 * deepest query is about 6 levels and its biggest about 130 fields; these limits leave it room to grow and refuse the
 * rest before running them. The standard introspection query needs depth 13.
 */
const val MAX_QUERY_DEPTH = 15
const val MAX_QUERY_FIELDS = 300

@Configuration
class GraphQLLimits {

    @Bean
    fun maxQueryDepth() = MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH)

    @Bean
    fun maxQueryComplexity() = MaxQueryComplexityInstrumentation(MAX_QUERY_FIELDS)
}
