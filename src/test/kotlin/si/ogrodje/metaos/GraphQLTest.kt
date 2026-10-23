package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import graphql.introspection.IntrospectionQuery
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class GraphQLTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val shows: ShowsRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val sessions: SessionsRepository,
    private val episodeDocuments: EpisodeDocumentsRepository,
    private val sessionDocuments: SessionDocumentsRepository,
    private val episodeFolders: EpisodeKoofrFoldersRepository,
    private val sessionFolders: SessionKoofrFoldersRepository,
    private val episodesMCP: EpisodesMCP,
    private val sessionsMCP: SessionsMCP,
    private val peopleMCP: PeopleMCP,
    private val details: DetailsGraphQL,
) {

    private val reader = jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", API_READ_SCOPE) }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // Changes made through MCP tools by someone signed in, so the audit log has who made them and with which tool
    private fun <T> asMcpUser(block: () -> T): T {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("ana-id").claim("preferred_username", "ana").claim("azp", "claude-code").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        return try { block() } finally { SecurityContextHolder.clearContext() }
    }

    @Test
    fun `shows signed-in users an episode with all its details and its history`() {
        asMcpUser {
            episodesMCP.create(code = "gq-h1", title = "Draft title")
            episodesMCP.update(
                code = "gq-h1", newCode = null, title = "Final title", titleB = "Other title", summary = "About it",
                tinySummary = "In short", machineSummary = "AI on it",
            )
            episodesMCP.assignPerson("gq-h1", checkNotNull(peopleMCP.create(name = "History Host", email = null).id), Role.HOST)
        }
        episodeFolders.save(EpisodeKoofrFolder(checkNotNull(episodes.findByCode("gq-h1")), "place-1", "/Epizode/S05E04 - Jure in Jošt (SAP)"))

        graphql(
            """{ episode(ref: "gq-h1") { title titleB titleC summary tinySummary machineSummary addedDate koofrFolders { path url }
                 history { actor tool kind summary fields { field from to } } } }""",
            reader,
        )
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.koofrFolders[0].url").value(
                "https://app.koofr.net/app/storage/place-1?path=%2FEpizode%2FS05E04%20-%20Jure%20in%20Jo%C5%A1t%20%28SAP%29"))
            .andExpect(jsonPath("$.data.episode.title").value("Final title"))
            .andExpect(jsonPath("$.data.episode.titleB").value("Other title"))
            .andExpect(jsonPath("$.data.episode.titleC").value(nullValue()))
            .andExpect(jsonPath("$.data.episode.tinySummary").value("In short"))
            .andExpect(jsonPath("$.data.episode.machineSummary").value("AI on it"))
            .andExpect(jsonPath("$.data.episode.history[*].summary", contains(
                "Created the episode", "Changed the episode", "Added History Host as host",
                "Linked the Koofr folder /Epizode/S05E04 - Jure in Jošt (SAP)",
            )))
            .andExpect(jsonPath("$.data.episode.history[*].kind", contains("EPISODE", "EPISODE", "PERSON", "KOOFR_FOLDER")))
            .andExpect(jsonPath("$.data.episode.history[0].actor").value("ana"))
            .andExpect(jsonPath("$.data.episode.history[0].tool").value("create_episode"))
            .andExpect(jsonPath("$.data.episode.history[1].fields[*].field", contains("Title", "Title B", "Summary", "Tiny summary", "AI summary")))
            .andExpect(jsonPath("$.data.episode.history[1].fields[0].from").value("Draft title"))
            .andExpect(jsonPath("$.data.episode.history[1].fields[0].to").value("Final title"))
            .andExpect(jsonPath("$.data.episode.history[1].fields[1].to").value("Other title"))
            // Too long to show
            .andExpect(jsonPath("$.data.episode.history[1].fields[2].to").value(null as Any?))
    }

    @Test
    fun `shows signed-in users a session with its episodes and its history`() {
        val sessionId = asMcpUser {
            val session = sessionsMCP.create(LocalDate.of(2099, 7, 1), location = "History Hall", address = "Hidden street 1")
            episodesMCP.create(code = "gq-h3", title = "Third")
            episodesMCP.create(code = "gq-h2", title = "Second")
            sessionsMCP.setEpisodeSession("gq-h3", session.id)
            sessionsMCP.setEpisodeSession("gq-h2", session.id)
            checkNotNull(session.id)
        }

        graphql("""{ session(id: "$sessionId") { location address episodes { code } history { kind summary fields { field to } } } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.session.address").value("Hidden street 1"))
            .andExpect(jsonPath("$.data.session.episodes[*].code", contains("gq-h2", "gq-h3")))
            .andExpect(jsonPath("$.data.session.history[*].summary",
                contains("Created the session", "Added gq-h3 to the session", "Added gq-h2 to the session")))
            .andExpect(jsonPath("$.data.session.history[*].kind", contains("SESSION", "EPISODE", "EPISODE")))
            .andExpect(jsonPath("$.data.session.history[0].fields[*].to", contains("1 Jul 2099", "History Hall", "Hidden street 1")))
    }

    @Test
    fun `keeps episodes' and sessions' details from anyone not signed in`() {
        val session = sessionsMCP.create(LocalDate.of(2099, 8, 1), location = "Closed Hall", address = "Hidden street 2")
        episodesMCP.create(code = "gq-h4", title = "Private")
        val episode = checkNotNull(episodes.findByCode("gq-h4")).apply { machineSummary = "AI on it"; titleB = "Other title"; this.session = sessions.findById(checkNotNull(session.id)).get() }
        episodes.save(episode)

        // An episode that isn't released is none, as one that isn't there, so its code doesn't say there is one
        graphql("""{ episode(ref: "gq-h4") { title } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode").value(null as Any?))
        graphql("""{ episode(ref: "${episode.id}") { title } }""")
            .andExpect(jsonPath("$.data.episode").value(null as Any?))
        graphql("""{ session(id: "${session.id}") { location } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))

        // Where an episode or session is public, e.g. among the released episodes, what's private stays empty
        val saved = checkNotNull(episodes.findByCode("gq-h4"))
        val savedSession = sessions.findById(checkNotNull(session.id)).get()
        assertNull(details.machineSummary(saved))
        assertNull(details.titleB(saved))
        assertEquals(emptyList(), details.episodeHistory(saved))
        assertNull(details.address(savedSession))
        assertEquals(emptyList(), details.sessionEpisodes(savedSession))
        assertEquals(emptyList(), details.sessionHistory(savedSession))
    }

    @Test
    fun `shows anyone a released episode's page, without what's the team's`() {
        asMcpUser {
            episodesMCP.create(code = "gq-pub1", title = "Public title")
            episodesMCP.update(
                code = "gq-pub1", newCode = null, title = null, titleB = "Other title", summary = "About it",
                tinySummary = "In short", machineSummary = "AI on it",
            )
        }
        val episode = episodes.save(checkNotNull(episodes.findByCode("gq-pub1")).apply { status = EpisodeStatus.PUBLISHED })
        episodePeople.save(EpisodePerson(episode = episode, person = people.save(Person(name = "Public Guest", email = null)), role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = episode, person = people.save(Person(name = "Public Candidate", email = null)), role = Role.CANDIDATE))
        episodeFolders.save(EpisodeKoofrFolder(episode, "place-1", "/Epizode/gq-pub1"))
        val fields = "code title summary tinySummary titleB machineSummary history { summary } koofrFolders { path } people { name }"

        graphql("""{ episode(ref: "gq-pub1") { $fields } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.title").value("Public title"))
            .andExpect(jsonPath("$.data.episode.summary").value("About it"))
            .andExpect(jsonPath("$.data.episode.tinySummary").value("In short"))
            .andExpect(jsonPath("$.data.episode.titleB").value(nullValue()))
            .andExpect(jsonPath("$.data.episode.machineSummary").value(nullValue()))
            .andExpect(jsonPath("$.data.episode.history", empty<Any>()))
            .andExpect(jsonPath("$.data.episode.koofrFolders", empty<Any>()))
            .andExpect(jsonPath("$.data.episode.people[*].name", contains("Public Guest")))
        // By its ID too
        graphql("""{ episode(ref: "${episode.id}") { code } }""")
            .andExpect(jsonPath("$.data.episode.code").value("gq-pub1"))
        // The team gets all of it
        graphql("""{ episode(ref: "gq-pub1") { $fields } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.titleB").value("Other title"))
            .andExpect(jsonPath("$.data.episode.history[*].summary", hasItem("Created the episode")))
            .andExpect(jsonPath("$.data.episode.people[*].name", contains("Public Guest", "Public Candidate")))
    }

    @Test
    fun `shows anyone the page of someone on a released episode, with only those episodes, and nobody else's`() {
        val aired = released("gq-pe1", LocalDate.of(2001, 1, 1))
        val idea = episodes.save(Episode(title = "gq-pe2, an idea", code = "gq-pe2"))
        val guest = people.save(Person(name = "Page Guest", email = null).apply { bio = "Engineer @ Somewhere" })
        val candidate = people.save(Person(name = "Page Candidate", email = null))
        val ideaOnly = people.save(Person(name = "Page Idea Guest", email = null))
        val nobody = people.save(Person(name = "Page Nobody", email = null))
        episodePeople.save(EpisodePerson(episode = aired, person = guest, role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = idea, person = guest, role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = aired, person = candidate, role = Role.CANDIDATE))
        episodePeople.save(EpisodePerson(episode = idea, person = ideaOnly, role = Role.GUEST))
        episodes.save(idea.apply { recommendedBy = guest })
        fun query(person: Person) = """{ person(id: "${person.id}") { name bio episodes { episode { code } roles } recommended { code } } }"""

        graphql(query(guest))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.person.name").value("Page Guest"))
            .andExpect(jsonPath("$.data.person.bio").value("Engineer @ Somewhere"))
            .andExpect(jsonPath("$.data.person.episodes[*].episode.code", contains("gq-pe1")))
            .andExpect(jsonPath("$.data.person.recommended", empty<Any>()))
        listOf(candidate, ideaOnly, nobody).forEach { person ->
            graphql(query(person))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.person").value(null as Any?))
        }

        // The team gets everyone, with all of their episodes
        graphql(query(guest), reader)
            .andExpect(jsonPath("$.data.person.episodes[*].episode.code", contains("gq-pe2", "gq-pe1")))
            .andExpect(jsonPath("$.data.person.recommended[*].code", contains("gq-pe2")))
        listOf(candidate, ideaOnly, nobody).forEach { person ->
            graphql(query(person), reader).andExpect(jsonPath("$.data.person.name").value(person.name))
        }
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions)
            .andExpect(status().isOk)
    }

    private fun released(code: String, aired: LocalDate?) = episodes.save(
        Episode(title = "Released $code", code = code).apply {
            status = EpisodeStatus.PUBLISHED
            airedDate = aired
            youtubeUrl = "https://www.youtube.com/watch?v=$code"
        }
    )

    @Test
    fun `lists episodes with their and their sessions' Koofr folders, which only signed-in users get`() {
        val day = sessions.save(Session(LocalDate.of(2001, 2, 3), location = "gq-k", address = null))
        val linked = released("gq-k1", LocalDate.of(2001, 2, 4)).apply { session = day }.let(episodes::save)
        released("gq-k2", LocalDate.of(2001, 2, 5))
        episodeFolders.save(EpisodeKoofrFolder(linked, "place-1", "/Epizode/gq-k1 b"))
        episodeFolders.save(EpisodeKoofrFolder(linked, "place-1", "/Epizode/gq-k1 a"))
        sessionFolders.save(SessionKoofrFolder(day, "place-1", "/Snemanja/2001-02-03"))
        val query = "{ episodes { code koofrFolders { path url } session { koofrFolders { path } } } }"

        // Each episode its own, by path
        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-k1')].koofrFolders[*].path", contains("/Epizode/gq-k1 a", "/Epizode/gq-k1 b")))
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-k1')].session.koofrFolders[*].path", contains("/Snemanja/2001-02-03")))
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-k2')].koofrFolders[*]", empty<Any>()))

        graphql(query)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-k1')].koofrFolders[*]", empty<Any>()))
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-k1')].session.koofrFolders[*]", empty<Any>()))
    }

    @Test
    fun `lists every episode but the ideas to signed-in users, and only the released ones to anyone else`() {
        released("gq-e1", LocalDate.of(2001, 1, 1))
        episodes.save(Episode(title = "Being made", code = "gq-e2").apply { status = EpisodeStatus.IN_PRODUCTION })
        episodes.save(Episode(title = "gq-e3, on hold without a code", code = null).apply { status = EpisodeStatus.ON_HOLD })
        episodes.save(Episode(title = "gq-e4, an idea", code = "gq-e4"))

        // Other tests' episodes are there too
        graphql("{ episodes { code title status } }", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[*].code", hasItems("gq-e1", "gq-e2")))
            .andExpect(jsonPath("$.data.episodes[*].title", hasItem("gq-e3, on hold without a code")))
            .andExpect(jsonPath("$.data.episodes[*].status", not(hasItem("IDEA"))))
            // Codes latest first, those without one last
            .andExpect(jsonPath("$.data.episodes[-1].code").value(nullValue()))

        graphql("{ episodes { code status } }")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[*].code", hasItem("gq-e1")))
            .andExpect(jsonPath("$.data.episodes[*].code", not(hasItem("gq-e2"))))
            .andExpect(jsonPath("$.data.episodes[*].status", everyItem(equalTo("PUBLISHED"))))
    }

    @Test
    fun `shows who recommended an episode to signed-in users only`() {
        val recommender = people.save(Person(name = "Recommending Person", email = null))
        episodes.save(released("gq-r1", LocalDate.of(2001, 1, 1)).apply { recommendedBy = recommender })
        val query = "{ episodes { code recommendedBy { id name } } }"

        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-r1')].recommendedBy.name", contains("Recommending Person")))
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-r1')].recommendedBy.id", contains(recommender.id.toString())))
        graphql(query)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-r1')].recommendedBy", contains(nullValue())))
    }

    @Test
    fun `shows an episode's candidates to signed-in users only`() {
        val episode = released("gq-c1", LocalDate.of(2001, 1, 1))
        episodePeople.save(EpisodePerson(episode = episode, person = people.save(Person(name = "Confirmed Guest", email = null)), role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = episode, person = people.save(Person(name = "Possible Guest", email = null)), role = Role.CANDIDATE))
        val query = "{ episodes { code people { name roles } } }"

        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-c1')].people[*].name", contains("Confirmed Guest", "Possible Guest")))
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-c1')].people[1].roles[0]", contains("CANDIDATE")))
        graphql(query)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episodes[?(@.code == 'gq-c1')].people[*].name", contains("Confirmed Guest")))
    }

    @Test
    fun `lists the ideas to signed-in users only, the most recently added first`() {
        episodes.save(Episode(title = "gq-i1, an old idea", code = null).apply { addedDate = LocalDate.of(2001, 1, 1) })
        episodes.save(Episode(title = "gq-i2, a new idea", code = "gq-i2").apply { addedDate = LocalDate.of(2099, 1, 1) })
        episodes.save(Episode(title = "gq-i3, planned", code = "gq-i3").apply { status = EpisodeStatus.PLANNED })

        // Other tests' ideas are there too, added in between
        graphql("{ ideas { title status } }", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.ideas[0].title").value("gq-i2, a new idea"))
            .andExpect(jsonPath("$.data.ideas[-1].title").value("gq-i1, an old idea"))
            .andExpect(jsonPath("$.data.ideas[*].title", not(hasItem("gq-i3, planned"))))
            .andExpect(jsonPath("$.data.ideas[*].status", everyItem(equalTo("IDEA"))))

        graphql("{ ideas { title } }")
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").value(null as Any?))
    }

    @Test
    fun `lists the latest released episodes to anyone, the most recently aired first, with their people`() {
        // Aired in 2099, so they come before whatever else the tests have published
        released("gq-2", LocalDate.of(2099, 2, 1))
        val latest = released("gq-3", LocalDate.of(2099, 3, 1))
        released("gq-1", LocalDate.of(2099, 1, 1))
        released("gq-0", LocalDate.of(2098, 12, 1))
        episodes.save(Episode(title = "Not released yet", code = "gq-9").apply { airedDate = LocalDate.of(2099, 9, 1) })

        val guest = people.save(Person(name = "Ana Guest", email = null))
        val host = people.save(Person(name = "Zala Host", email = "zala.host@example.com"))
        episodePeople.save(EpisodePerson(episode = latest, person = guest, role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = latest, person = host, role = Role.PRODUCER))
        episodePeople.save(EpisodePerson(episode = latest, person = host, role = Role.HOST))
        episodeDocuments.save(EpisodeDocument(latest, "doc-of-gq-3"))
        episodes.save(latest.apply { show = shows.save(Show(name = "gq Show", color = "#0404a4")); tinySummary = "In short" })

        graphql("{ releasedEpisodes { code title show showColor airedDate youtubeUrl tinySummary people { name roles } documents { url } } }")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.releasedEpisodes[*].code", contains("gq-3", "gq-2", "gq-1")))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].show").value("gq Show"))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].showColor").value("#0404a4"))
            .andExpect(jsonPath("$.data.releasedEpisodes[1].show").value(nullValue()))
            .andExpect(jsonPath("$.data.releasedEpisodes[1].showColor").value(nullValue()))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].airedDate").value("2099-03-01"))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].youtubeUrl").value("https://www.youtube.com/watch?v=gq-3"))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].tinySummary").value("In short"))
            .andExpect(jsonPath("$.data.releasedEpisodes[1].tinySummary").value(nullValue()))
            // Each person once, with all of their roles; hosts first
            .andExpect(jsonPath("$.data.releasedEpisodes[0].people[*].name", contains("Zala Host", "Ana Guest")))
            .andExpect(jsonPath("$.data.releasedEpisodes[0].people[0].roles", contains("HOST", "PRODUCER")))
            .andExpect(jsonPath("$.data.releasedEpisodes[1].people", empty<Any>()))
            // Docs are the team's
            .andExpect(jsonPath("$.data.releasedEpisodes[0].documents", empty<Any>()))

        graphql("{ releasedEpisodes(limit: 4) { code } }")
            .andExpect(jsonPath("$.data.releasedEpisodes[3].code").value("gq-0"))
    }

    @Test
    fun `shows signed-in users the upcoming recordings and what's in production, by recording date`() {
        val session = sessions.save(Session(date = LocalDate.of(2099, 6, 1), location = "GraphQL Conf", address = null))
        fun episode(code: String, status: EpisodeStatus, init: Episode.() -> Unit = {}) =
            episodes.save(Episode(title = "Episode $code", code = code).apply { this.status = status; init() })
        episode("gq-u2", EpisodeStatus.IDEA) { this.session = session }
        episode("gq-u1", EpisodeStatus.SCHEDULED) { recordingDate = LocalDate.of(2099, 5, 1) }
        episode("gq-u9", EpisodeStatus.IDEA)
        episode("gq-p2", EpisodeStatus.EARLY_ACCESS) { recordingDate = LocalDate.of(2099, 4, 2) }
        episode("gq-p1", EpisodeStatus.IN_PRODUCTION) { recordingDate = LocalDate.of(2099, 4, 1); this.session = session }
        episodeDocuments.save(EpisodeDocument(episodes.findByCode("gq-u1")!!, "doc-of-gq-u1"))
        sessionDocuments.save(SessionDocument(session, "plan-of-graphql-conf"))

        val answer = graphql(
            "{ upcomingRecordings { code documents { url } session { location documents { fileId } } } inProduction { code status } }",
            reader,
        )
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn().response.contentAsString

        // Other tests' episodes may be there too. An idea without a session or a date (gq-u9) isn't a recording yet.
        val upcoming = JsonPath.read<List<String>>(answer, "$.data.upcomingRecordings[*].code").filter { it.startsWith("gq-u") }
        val inProduction = JsonPath.read<List<String>>(answer, "$.data.inProduction[*].code").filter { it.startsWith("gq-p") }
        assertEquals(listOf("gq-u1", "gq-u2"), upcoming)
        assertEquals(listOf("gq-p1", "gq-p2"), inProduction)
        assertEquals(listOf("GraphQL Conf"), JsonPath.read(answer, "$.data.upcomingRecordings[?(@.code == 'gq-u2')].session.location"))
        assertEquals(listOf("EARLY_ACCESS"), JsonPath.read(answer, "$.data.inProduction[?(@.code == 'gq-p2')].status"))
        assertEquals(
            listOf("https://docs.google.com/document/d/doc-of-gq-u1/edit"),
            JsonPath.read(answer, "$.data.upcomingRecordings[?(@.code == 'gq-u1')].documents[*].url"),
        )
        assertEquals(
            listOf("plan-of-graphql-conf"),
            JsonPath.read(answer, "$.data.upcomingRecordings[?(@.code == 'gq-u2')].session.documents[*].fileId"),
        )
    }

    @Test
    fun `keeps what isn't public from anyone without metaos read`() {
        graphql("{ upcomingRecordings { code } }")
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").value(null as Any?))
        graphql("{ inProduction { code } }", jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", MCP_SCOPE) })
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
            .andExpect(jsonPath("$.data").value(null as Any?))
        // metaos:read alone isn't enough: the user has to be approved (Security.kt)
        graphql("{ inProduction { code } }", jwt().jwt { it.claim("scope", API_READ_SCOPE) })
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
            .andExpect(jsonPath("$.data").value(null as Any?))
    }

    @Test
    fun `turns down a limit out of range`() {
        graphql("{ releasedEpisodes(limit: 500) { code } }")
            .andExpect(jsonPath("$.errors[0].message").value("limit must be between 1 and $MAX_RELEASED_EPISODES"))
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
    }

    @Test
    fun `knows nobody without a token`() {
        graphql("{ me { username } }").andExpect(jsonPath("$.data.me").value(null as Any?))
    }

    @Test
    fun `tells the signed-in user who they are`() {
        people.save(Person(name = "GraphQL Tester", email = "graphql.tester@example.com"))
        val token = jwt().jwt {
            it.claim("preferred_username", "tester")
            it.claim("email", "graphql.tester@example.com")
            it.claim("email_verified", true)
        }

        graphql("{ me { username emailVerified person { name episodes { roles } } } }", token)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.me.username").value("tester"))
            .andExpect(jsonPath("$.data.me.emailVerified").value(true))
            .andExpect(jsonPath("$.data.me.person.name").value("GraphQL Tester"))
            .andExpect(jsonPath("$.data.me.person.episodes", empty<Any>()))
    }

    @Test
    fun `lists everyone by name to signed-in users, and those on the released episodes to anyone else`() {
        val aired = released("gq-pp1", LocalDate.of(2001, 1, 1))
        val idea = episodes.save(Episode(title = "gq-pp2, an idea", code = "gq-pp2"))
        val guest = people.save(Person(name = "Čedo People", email = "cedo.people@example.com").apply {
            bio = "Engineer @ Somewhere"
            setSocialLink(SocialNetwork.LINKEDIN, "https://www.linkedin.com/in/cedo/")
        })
        val host = people.save(Person(name = "dr. Ana People", email = null))
        val candidate = people.save(Person(name = "Cene People", email = null))
        people.save(Person(name = "Dana People", email = null))
        episodePeople.save(EpisodePerson(episode = aired, person = guest, role = Role.GUEST))
        episodePeople.save(EpisodePerson(episode = idea, person = guest, role = Role.CANDIDATE))
        episodePeople.save(EpisodePerson(episode = aired, person = host, role = Role.PRODUCER))
        episodePeople.save(EpisodePerson(episode = aired, person = host, role = Role.HOST))
        episodePeople.save(EpisodePerson(episode = aired, person = candidate, role = Role.CANDIDATE))
        val query = "{ people { name bio linkedinUrl episodes { episode { code } roles } } }"
        // Other tests' people are there too
        fun ours(answer: ResultActions) = JsonPath.read<List<String>>(answer.andReturn().response.contentAsString, "$.data.people[*].name")
            .filter { it.endsWith(" People") }

        val signedIn = graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.people[?(@.name == 'Čedo People')].bio", contains("Engineer @ Somewhere")))
            .andExpect(jsonPath("$.data.people[?(@.name == 'Čedo People')].linkedinUrl", contains("https://www.linkedin.com/in/cedo/")))
            // The latest first, each once with all of the person's roles
            .andExpect(jsonPath("$.data.people[?(@.name == 'Čedo People')].episodes[*].episode.code", contains("gq-pp2", "gq-pp1")))
            .andExpect(jsonPath("$.data.people[?(@.name == 'dr. Ana People')].episodes[*].roles[*]", contains("HOST", "PRODUCER")))
        // Slovenian order, past the title
        assertEquals(listOf("dr. Ana People", "Cene People", "Čedo People", "Dana People"), ours(signedIn))

        val anyone = graphql(query)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.people[?(@.name == 'Čedo People')].episodes[*].episode.code", contains("gq-pp1")))
            .andExpect(jsonPath("$.data.people[?(@.name == 'Čedo People')].episodes[*].roles[*]", contains("GUEST")))
        assertEquals(listOf("dr. Ana People", "Čedo People"), ours(anyone))
    }

    // People -> their episodes -> who recommended them -> ..., as deep as asked
    private fun nested(loops: Int) =
        "{ people { " + "episodes { episode { recommendedBy { ".repeat(loops) + "name" + " } } }".repeat(loops) + " } }"

    @Test
    fun `refuses queries nested deeper than the limit, before running them`() {
        released("gq-deep", LocalDate.of(2001, 3, 4))
        graphql(nested(4)).andExpect(jsonPath("$.errors").doesNotExist())
        graphql(nested(5))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.errors[0].message", containsString("maximum query depth exceeded")))
    }

    @Test
    fun `answers the standard introspection query, for GraphiQL and code generators`() {
        graphql(IntrospectionQuery.INTROSPECTION_QUERY).andExpect(jsonPath("$.errors").doesNotExist())
    }

    @Test
    fun `refuses queries asking for more fields than the limit, even through aliases`() {
        val many = (1..MAX_QUERY_FIELDS).joinToString(" ") { "e$it: releasedEpisodes(limit: 1) { code }" }
        graphql("{ $many }")
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.errors[0].message", containsString("maximum query complexity exceeded")))
    }
}
