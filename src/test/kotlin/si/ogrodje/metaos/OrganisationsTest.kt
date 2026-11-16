package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class OrganisationsTest @Autowired constructor(
    private val mvc: MockMvc,
    private val organisationsMCP: OrganisationsMCP,
    private val organisations: Organisations,
    private val peopleMCP: PeopleMCP,
    private val episodesMCP: EpisodesMCP,
    private val episodes: EpisodesRepository,
    private val recoveryMCP: RecoveryMCP,
    private val auditMCP: AuditMCP,
    private val history: History,
) {

    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    @Test
    fun `has ogrodje si's supporters and partners, supporters first`() {
        val supporters = organisationsMCP.list(supporting = true).filter { it.id.toString().startsWith("6f0b7c1e-") }
        assertEquals(
            listOf("Kaldi", "Koofr", "NGEN Group", "Visionect", "Monitor", "Računalniški muzej", "SLO AI", "Spletne Urice Maribor"),
            supporters.map { it.name },
        )
        assertTrue(supporters.take(4).all { it.tier == OrganisationTier.SUPPORTER })
        assertTrue(supporters.drop(4).all { it.tier == OrganisationTier.PARTNER && it.supporting })
    }

    @Test
    fun `creates and changes an organisation, and clears what's empty`() {
        val contact = peopleMCP.create(name = "Contact Person", email = null)
        val created = organisationsMCP.create(name = " Acme Test ", description = "Makes things", websiteUrl = "https://acme.test")
        assertEquals("Acme Test", created.name)
        assertEquals(OrganisationTier.NONE, created.tier)
        assertFalse(created.supporting)

        val id = checkNotNull(created.id)
        val changed = organisationsMCP.update(
            id, tier = OrganisationTier.SUPPORTER, supportStartedAt = "2026-01-01", vatId = " SI12345678 ",
            companyId = "1234567000", address = "Ulica 1, Ljubljana", contactPerson = contact.id.toString(),
            linkedinUrl = "https://www.linkedin.com/company/acme",
        )
        assertEquals(LocalDate.of(2026, 1, 1), changed.supportStartedAt)
        assertEquals("SI12345678", changed.vatId)
        assertEquals("Contact Person", changed.contactPerson)
        assertEquals("https://acme.test", changed.websiteUrl)
        assertTrue(changed.supporting)
        assertTrue(organisationsMCP.list(supporting = true).any { it.id == id })

        // Its support ended yesterday: not listed anymore
        val ended = organisationsMCP.update(id, supportEndedAt = ogrodjeToday().minusDays(1).toString(), description = "")
        assertFalse(ended.supporting)
        assertNull(ended.description)
        assertTrue(organisationsMCP.list(supporting = true).none { it.id == id })

        assertFailsWith<IllegalArgumentException> { organisationsMCP.create(name = "ACME TEST") }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(id, supportEndedAt = "2025-12-31") }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(id, supportStartedAt = "1. 1. 2026") }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(id, linkedinUrl = "https://acme.test") }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(id, contactPerson = "nobody") }

        assertEquals(
            listOf("Created the organisation", "Changed the organisation", "Changed the organisation"),
            history.of(id).map { it.summary },
        )
        assertEquals(
            listOf("Tier", "Supporting since", "LinkedIn", "VAT ID", "Company ID", "Address", "Contact"),
            history.of(id)[1].fields.map { it.field },
        )
        assertEquals("Contact Person", history.of(id)[1].fields.last().to)
    }

    @Test
    fun `says where people work, and who works where`() {
        val person = checkNotNull(peopleMCP.create(name = "Worker Bee", email = null).id)
        val organisation = checkNotNull(organisationsMCP.create(name = "Hive Test").id)
        val other = checkNotNull(organisationsMCP.create(name = "Old Hive Test").id)

        val work = organisationsMCP.addWork(person, organisation, title = "CTO", startedAt = "2024-03-01")
        assertFailsWith<IllegalArgumentException> { organisationsMCP.addWork(person, organisation) }
        organisationsMCP.addWork(person, other, title = "Developer", startedAt = "2020-01-01", endedAt = "2023-12-31")
        assertFailsWith<IllegalArgumentException> { organisationsMCP.addWork(person, other, startedAt = "2020-01-01", endedAt = "2019-01-01") }

        assertEquals(listOf("Hive Test", "Old Hive Test"), organisationsMCP.listWork(person).map { it.organisation })
        assertEquals(listOf("Worker Bee"), organisationsMCP.get(organisation).people.map { it.person })

        organisationsMCP.updateWork(checkNotNull(work.id), title = "")
        assertNull(organisationsMCP.listWork(person).first().title)

        val removed = organisationsMCP.removeWork(checkNotNull(work.id))
        assertEquals(listOf("Old Hive Test"), organisationsMCP.listWork(person).map { it.organisation })
        assertEquals(
            listOf("Added Worker Bee, who works there", "Changed Worker Bee's work there", "Removed Worker Bee, who worked there"),
            history.of(organisation).filter { it.kind == ChangeKind.ORGANISATION && it.summary.contains("Worker") }.map { it.summary },
        )

        // And back, from the audit log
        val change = auditMCP.history(organisation).last { it.table == "person_organisations" && it.operation == "DELETE" }
        recoveryMCP.restoreLink(change.id)
        assertEquals(listOf(removed.id), organisationsMCP.get(organisation).people.map { it.id })
    }

    @Test
    fun `says who supported an episode, once, and keeps them when it's deleted and restored`() {
        episodesMCP.create(code = "org-101", title = "Supported")
        val organisation = checkNotNull(organisationsMCP.create(name = "Sponsor Test").id)

        organisationsMCP.addSupporter("org-101", organisation)
        assertEquals(listOf("Sponsor Test"), organisationsMCP.addSupporter("ORG-101", organisation).map { it.name })
        assertEquals(listOf("org-101"), organisationsMCP.get(organisation).supportedEpisodes.map { it.code })
        assertEquals(listOf("Added Sponsor Test as a supporter"), history.of(checkNotNull(episodes.findByCode("org-101")?.id)).drop(1).map { it.summary })

        organisationsMCP.delete(organisation)
        assertTrue(organisationsMCP.listSupporters("org-101").isEmpty())
        assertTrue(recoveryMCP.listDeleted(DeletedKind.ORGANISATION).any { it.id == organisation && it.name == "Sponsor Test" })
        organisationsMCP.create(name = "Sponsor Test")
        assertFailsWith<IllegalArgumentException> { organisationsMCP.restore(organisation) }
        organisationsMCP.restore(organisation, newName = "Sponsor Test Again")
        assertEquals(listOf("Sponsor Test Again"), organisationsMCP.listSupporters("org-101").map { it.name })

        assertTrue(organisationsMCP.removeSupporter("org-101", organisation).isEmpty())
    }

    @Test
    fun `shows supporters to anyone, and the rest only to the team`() {
        val organisation = organisationsMCP.create(name = "Public Test", tier = OrganisationTier.PARTNER)
        val id = checkNotNull(organisation.id)
        organisationsMCP.update(id, vatId = "SI87654321", supportStartedAt = "2025-05-05")
        val person = checkNotNull(peopleMCP.create(name = "Public Worker", email = null).id)
        organisationsMCP.addWork(person, id, title = "CEO")
        episodesMCP.create(code = "org-201", title = "Released and supported")
        episodesMCP.create(code = "org-202", title = "Not out yet")
        episodes.save(checkNotNull(episodes.findByCode("org-201")).apply { status = EpisodeStatus.PUBLISHED })
        organisationsMCP.addSupporter("org-201", id)
        organisationsMCP.addSupporter("org-202", id)

        graphql("""{ supporters { name tier supporting vatId supportStartedAt people { title } episodes { code } } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.supporters[?(@.name == 'Public Test')].tier", contains("PARTNER")))
            .andExpect(jsonPath("$.data.supporters[?(@.name == 'Public Test')].vatId", contains(null as Any?)))
            .andExpect(jsonPath("$.data.supporters[?(@.name == 'Public Test')].supportStartedAt", contains(null as Any?)))
            .andExpect(jsonPath("$.data.supporters[?(@.name == 'Public Test')].episodes[*].code", contains("org-201")))
            .andExpect(jsonPath("$.data.supporters[?(@.name == 'Public Test')].people[*]").isEmpty)
        graphql("""{ episode(ref: "org-201") { supportedBy { name } } }""")
            .andExpect(jsonPath("$.data.episode.supportedBy[*].name", contains("Public Test")))
        graphql("""{ organisations { name } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))

        graphql("""{ organisation(id: "$id") { vatId supportStartedAt people { title person { name } } episodes { code } } }""", reader)
            .andExpect(jsonPath("$.data.organisation.vatId").value("SI87654321"))
            .andExpect(jsonPath("$.data.organisation.supportStartedAt").value("2025-05-05"))
            .andExpect(jsonPath("$.data.organisation.people[0].person.name").value("Public Worker"))
            .andExpect(jsonPath("$.data.organisation.episodes[*].code", contains("org-202", "org-201")))
        graphql("""{ person(id: "$person") { organisations { title current organisation { name } } } }""", reader)
            .andExpect(jsonPath("$.data.person.organisations[0].title").value("CEO"))
            .andExpect(jsonPath("$.data.person.organisations[0].current").value(true))
        graphql("""{ organisations { name } }""", reader)
            .andExpect(jsonPath("$.data.organisations[*].name", hasItem("Public Test")))
    }

    @Test
    fun `changes organisations over GraphQL with metaos write`() {
        val create = """mutation { createOrganisation(input: { name: "Graph Test", tier: SUPPORTER, websiteUrl: "https://graph.test" }) { id name tier supporting } }"""
        graphql(create, reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        val answer = graphql(create, writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createOrganisation.supporting").value(true))
        val id = UUID.fromString(JsonPath.read(answer.andReturn().response.contentAsString, "$.data.createOrganisation.id"))

        graphql("""mutation { updateOrganisation(id: "$id", input: { tier: NONE, address: "Cesta 2" }) { tier address websiteUrl } }""", writer)
            .andExpect(jsonPath("$.data.updateOrganisation.tier").value("NONE"))
            .andExpect(jsonPath("$.data.updateOrganisation.address").value("Cesta 2"))
            .andExpect(jsonPath("$.data.updateOrganisation.websiteUrl").value("https://graph.test"))
        graphql("""mutation { updateOrganisation(id: "$id", input: { supportStartedAt: "soon" }) { id } }""", writer)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))

        val person = checkNotNull(peopleMCP.create(name = "Graph Worker", email = null).id)
        val added = graphql("""mutation { addPersonToOrganisation(person: "$person", organisation: "$id", title: "Designer", startedAt: "2026-02-02") { id title startedAt current } }""", writer)
            .andExpect(jsonPath("$.data.addPersonToOrganisation.startedAt").value("2026-02-02"))
        val work = JsonPath.read<String>(added.andReturn().response.contentAsString, "$.data.addPersonToOrganisation.id")
        graphql("""mutation { updatePersonOrganisation(id: "$work", endedAt: "2026-03-03") { endedAt current } }""", writer)
            .andExpect(jsonPath("$.data.updatePersonOrganisation.current").value(false))
        graphql("""mutation { removePersonFromOrganisation(id: "$work") }""", writer)
            .andExpect(jsonPath("$.data.removePersonFromOrganisation").value(work))

        episodesMCP.create(code = "org-301", title = "Graph supported")
        graphql("""mutation { addEpisodeSupporter(ref: "org-301", organisation: "$id") { supportedBy { name } } }""", writer)
            .andExpect(jsonPath("$.data.addEpisodeSupporter.supportedBy[*].name", contains("Graph Test")))
        graphql("""mutation { removeEpisodeSupporter(ref: "org-301", organisation: "$id") { supportedBy { name } } }""", writer)
            .andExpect(jsonPath("$.data.removeEpisodeSupporter.supportedBy").isEmpty)

        graphql("""mutation { deleteOrganisation(id: "$id") }""", writer).andExpect(jsonPath("$.data.deleteOrganisation").value(id.toString()))
        graphql("""{ organisations { name } }""", reader).andExpect(jsonPath("$.data.organisations[*].name", not(hasItem("Graph Test"))))
        assertTrue(organisations.all().none { it.id == id })
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions)
            .andExpect(status().isOk)
    }
}
