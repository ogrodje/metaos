<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeRow from '@/components/EpisodeRow.vue'
import EventForm from '@/components/EventForm.vue'
import EventRow from '@/components/EventRow.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import MeetupBadge from '@/components/MeetupBadge.vue'
import NotFound from '@/components/NotFound.vue'
import OrganisationForm from '@/components/OrganisationForm.vue'
import OrganisationLogo from '@/components/OrganisationLogo.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { type Episode, EPISODE_FIELDS, LIST_PICTURE_FIELDS } from '@/episodes'
import { mapUrl, placeLine, sourceName, TEAM_EVENT_FIELDS, type TeamEvent } from '@/events'
import { useEventsRefresh } from '@/eventsRefresh'
import { errorMessage, graphql } from '@/graphql'
import { TIME_ZONE } from '@/i18n'
import { type Image, IMAGE_FIELDS, src } from '@/images'
import {
  DELETE_ORGANISATION, EVENT_LINKS, EVENT_SCRAPE_FIELDS, type EventScrape, type Organisation, ORGANISATION_FIELDS,
  ORGANISATIONS_PATH, siteName, tierName, UPDATE_ORGANISATION, type Work, WORK_FIELDS, workPeriod,
} from '@/organisations'
import { personPath } from '@/people'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { useLocale } from '@/useLocale'

/** An organisation with who works there and the episodes it supported, for its page */
interface OrganisationDetails extends Organisation {
  people: (Work & { person: { id: string, name: string, avatar: Image | null } })[]
  episodes: Episode[]
  events: TeamEvent[]
  eventScrapes: EventScrape[]
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const { t, dateLocale, path } = useLocale()

const id = String(route.params.id)

// Undefined while it loads; null when there's no such organisation
const organisation = ref<OrganisationDetails | null>()
const error = ref<string>()

let loads = 0

// The team's, as is the page (it renders in the browser only); quietly when MetaOS's data changes
async function load(quietly = false) {
  const load = ++loads
  if (!auth.team) return
  try {
    const data = await graphql<{ organisation: OrganisationDetails | null }>(`query ($id: ID!) {
      organisation(id: $id) {
        ${ORGANISATION_FIELDS}
        people { ${WORK_FIELDS} person { id name avatar { ${IMAGE_FIELDS} } } }
        episodes { ${EPISODE_FIELDS} ${LIST_PICTURE_FIELDS} }
        events { ${TEAM_EVENT_FIELDS} }
        eventScrapes { ${EVENT_SCRAPE_FIELDS} }
      }
    }`, { id })
    if (load !== loads) return
    organisation.value = keepUnchanged(organisation.value, data.organisation)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e, t)
  }
}

watch(() => auth.team, () => void load(), { immediate: true })
useLiveRefresh(() => load(true))

usePageTitle(() => organisation.value?.name)

// Who works there now, then who did
const current = computed(() => (organisation.value?.people ?? []).filter((work) => work.current))
const former = computed(() => (organisation.value?.people ?? []).filter((work) => !work.current))
const workLists = computed(() => [
  { id: 'people', title: t('organisation.people'), works: current.value },
  { id: 'former', title: t('organisation.former'), works: former.value },
].filter((list) => list.works.length))

// Its links, as SocialLinks shows a person's
const links = computed(() => organisation.value && {
  name: organisation.value.name,
  homepageUrl: organisation.value.websiteUrl,
  linkedinUrl: organisation.value.linkedinUrl,
  twitterUrl: organisation.value.twitterUrl,
  instagramUrl: organisation.value.instagramUrl,
  facebookUrl: organisation.value.facebookUrl,
})

const day = (date: string) =>
  new Date(`${date}T12:00:00Z`).toLocaleDateString(dateLocale.value, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

// How it supports Ogrodje, in a line under its name, e.g. Podporniki, since 1 Jan 2026
const support = computed(() => {
  const shown = organisation.value
  if (!shown || shown.tier === 'NONE') return null
  return shown.supporting ? tierName(shown.tier, t) : t('organisations.formerly', { tier: tierName(shown.tier, t) })
})

// The team changes it here: Edit, Archive or Bring back, and Delete, asked first
const editing = ref(false)
const confirmingDelete = ref(false)
const busy = ref(false)
const actionError = ref<string>()

function saved(changed: Organisation) {
  if (organisation.value) organisation.value = { ...organisation.value, ...changed }
}

function stopEditing() {
  editing.value = false
  void nextTick(() => document.getElementById('edit-organisation')?.focus())
}

async function setArchived(archived: boolean) {
  if (busy.value || !organisation.value) return
  busy.value = true
  actionError.value = undefined
  try {
    const data = await graphql<{ organisation: Organisation }>(UPDATE_ORGANISATION, { id, input: { archived } })
    saved(data.organisation)
  } catch (e) {
    actionError.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

function askToDelete() {
  actionError.value = undefined
  confirmingDelete.value = true
  void nextTick(() => document.getElementById('keep-organisation')?.focus())
}

function keep() {
  confirmingDelete.value = false
  void nextTick(() => document.getElementById('delete-organisation')?.focus())
}

async function remove() {
  if (busy.value) return
  busy.value = true
  actionError.value = undefined
  try {
    await graphql(DELETE_ORGANISATION, { id })
    await router.push(path(ORGANISATIONS_PATH))
  } catch (e) {
    actionError.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

// Its events: refreshed from its links now, added by the team, and each hidden, picked or changed in its row
const { running: refreshing, starting: refreshStarting, error: refreshError, start: startRefresh } =
  useEventsRefresh(() => id, () => load(true).catch(() => undefined))
const addingEvent = ref(false)
const eventsOpen = ref(false)
const EVENTS_SHOWN = 10
const events = computed(() => {
  const all = organisation.value?.events ?? []
  return eventsOpen.value ? all : all.slice(0, EVENTS_SHOWN)
})

function eventChanged(event: TeamEvent) {
  if (!organisation.value) return
  organisation.value = { ...organisation.value, events: organisation.value.events.map((each) => (each.id === event.id ? event : each)) }
}

function eventAdded() {
  void load(true).catch(() => undefined)
}

// Its badge on the Events page, with its logo
const badge = computed(() => organisation.value && {
  name: organisation.value.name,
  logo: organisation.value.logo ? src(organisation.value.logo, 40) : null,
  mainColor: organisation.value.mainColor,
  backgroundColor: organisation.value.backgroundColor,
})

// Where its events usually are, which those that don't say where they are go by, with a link to the map
const venuePlace = computed(() => organisation.value && { name: organisation.value.venueName, address: organisation.value.venueAddress })
const venue = computed(() => placeLine(venuePlace.value))
const venueMap = computed(() => mapUrl(venuePlace.value))

// Where it announces its events, and how each was last scraped: its website and its event links
const eventLinks = computed(() => {
  const shown = organisation.value
  if (!shown) return []
  const links = [
    { key: 'websiteUrl', field: 'WEBSITE', name: t('person.website'), url: shown.websiteUrl },
    ...EVENT_LINKS.map((link) => ({ key: link.key, field: link.field, name: link.name, url: shown[link.key] })),
  ]
  return links.filter((link) => link.url).map((link) => ({
    ...link,
    url: link.url!,
    scrape: shown.eventScrapes.find((scrape) => scrape.field === link.field && scrape.url === link.url) ?? null,
  }))
})

const scrapedAt = (at: string) =>
  new Date(at).toLocaleString(dateLocale.value, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })

// Which scraper read it, else that none does, how many it found, and when; why it failed under it
function scrapeLine(scrape: EventScrape) {
  const found = t('organisation.events.found', { n: scrape.events }, scrape.events)
  if (scrape.error) return [scrape.source ? sourceName(scrape.source) : null, scrapedAt(scrape.scrapedAt)].filter(Boolean).join(' · ')
  if (!scrape.source) return `${t('organisation.events.noScraper')} · ${scrapedAt(scrape.scrapedAt)}`
  return `${sourceName(scrape.source)} · ${found} · ${scrapedAt(scrape.scrapedAt)}`
}

const hasFinance = computed(() => {
  const shown = organisation.value
  return !!shown && !!(shown.vatId || shown.companyId || shown.address || shown.contactPerson)
})
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('organisations.signInTitle')">
      {{ t('organisations.signInOne') }}
    </SignInPrompt>
    <p v-else-if="auth.ready && !auth.team" class="note">{{ t('organisations.staffOnly') }}</p>
    <LoadError v-else-if="error" :what="t('organisation.what')" :error="error" @retry="load()"/>
    <LoadingRows v-else-if="organisation === undefined" :rows="6" :label="t('organisation.loading')"/>
    <NotFound v-else-if="organisation === null" :title="t('organisation.notFound')">{{ t('organisation.notFoundText') }}</NotFound>

    <!-- What it is, as a person's page has who they are; at the right its support, links and finance details; under
         them who works there and the episodes it supported -->
    <div v-else class="workspace">
      <div class="lead">
        <OrganisationForm v-if="editing" :organisation="organisation" @saved="saved" @close="stopEditing"/>
        <header v-else class="organisation">
          <OrganisationLogo :image="organisation.logo" :name="organisation.name" :size="96"/>
          <div class="title">
            <h1>{{ organisation.name }}</h1>
            <p v-if="support || organisation.archived" class="meta">
              {{ [organisation.archived ? t('organisation.archived') : null, support].filter(Boolean).join(' · ') }}
            </p>
            <p v-if="organisation.description" class="subtitle">{{ organisation.description }}</p>
          </div>
        </header>
        <div v-if="!editing" class="manage">
          <span v-if="confirmingDelete" class="confirm" role="group" aria-labelledby="delete-ask" @keydown.esc="keep">
            <span id="delete-ask">{{ t('organisation.deleteAsk', { name: organisation.name }) }}</span>
            <button type="button" class="button small danger" :aria-disabled="busy || undefined" @click="remove">
              {{ busy ? t('organisation.deleting') : t('organisation.delete') }}
            </button>
            <button id="keep-organisation" type="button" class="button small" :disabled="busy" @click="keep">{{ t('common.cancel') }}</button>
          </span>
          <template v-else>
            <button id="edit-organisation" type="button" class="button small" @click="editing = true">{{ t('common.edit') }}</button>
            <button type="button" class="button small quiet" :aria-disabled="busy || undefined" @click="setArchived(!organisation.archived)">
              {{ organisation.archived ? t('organisation.unarchive') : t('organisation.archive') }}
            </button>
            <button id="delete-organisation" type="button" class="button small quiet" @click="askToDelete">{{ t('organisation.delete') }}</button>
          </template>
          <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
        </div>
      </div>

      <FactPanel :label="t('organisation.about', { name: organisation.name })">
        <FactGroup v-if="organisation.tier !== 'NONE'" :title="t('organisation.support')">
          <dl>
            <Fact :label="t('organisation.tier')">{{ tierName(organisation.tier, t) }}</Fact>
            <Fact v-if="organisation.supportStartedAt" :label="t('organisation.since')">{{ day(organisation.supportStartedAt) }}</Fact>
            <Fact v-if="organisation.supportEndedAt" :label="t('organisation.until')">{{ day(organisation.supportEndedAt) }}</Fact>
            <Fact :label="t('organisation.listed')">{{ organisation.supporting ? t('organisation.listedYes') : t('organisation.listedNo') }}</Fact>
          </dl>
        </FactGroup>
        <FactGroup v-if="organisation.websiteUrl || organisation.linkedinUrl || organisation.twitterUrl || organisation.instagramUrl || organisation.facebookUrl">
          <dl>
            <Fact :label="t('person.online')"><span class="icons"><SocialLinks v-if="links" :person="links"/></span></Fact>
            <Fact v-if="organisation.websiteUrl" :label="t('person.website')">
              <a :href="organisation.websiteUrl">{{ siteName(organisation.websiteUrl) }}</a>
            </Fact>
          </dl>
        </FactGroup>
        <FactGroup :title="t('organisation.events.title')">
          <dl>
            <Fact :label="t('organisation.events.gathers')">
              {{ organisation.collectsEvents ? t('organisation.events.gathersYes') : t('organisation.events.gathersNo') }}
            </Fact>
            <Fact v-if="badge" :label="t('organisation.events.badge')"><MeetupBadge :meetup="badge"/></Fact>
            <Fact v-if="venue" :label="t('organisation.venue')" stacked>
              <a v-if="venueMap" :href="venueMap" target="_blank" rel="noopener">{{ venue }}</a>
              <template v-else>{{ venue }}</template>
            </Fact>
            <Fact v-for="link in eventLinks" :key="link.key" :label="link.name" stacked>
              <a :href="link.url" target="_blank" rel="noopener">{{ siteName(link.url) }}</a>
              <template v-if="link.field !== 'DISCORD'">
                <span v-if="!link.scrape" class="scrape">{{ t('organisation.events.notScraped') }}</span>
                <template v-else>
                  <span class="scrape">{{ scrapeLine(link.scrape) }}</span>
                  <span v-if="link.scrape.error" class="scrape failed">{{ link.scrape.error }}</span>
                </template>
              </template>
            </Fact>
          </dl>
          <p v-if="!eventLinks.length" class="panel-note">{{ t('organisation.events.noLinks') }}</p>
          <div v-if="organisation.collectsEvents && eventLinks.length" class="refresh">
            <button type="button" class="button small" :aria-disabled="refreshing || refreshStarting || undefined" @click="startRefresh()">
              {{ refreshing || refreshStarting ? t('events.team.refreshing') : t('events.team.refresh') }}
            </button>
            <p v-if="refreshError" class="error" role="alert">{{ t('events.team.notRefreshed', { error: refreshError }) }}</p>
          </div>
        </FactGroup>
        <FactGroup v-if="hasFinance" :title="t('organisation.finance')">
          <dl>
            <Fact v-if="organisation.vatId" :label="t('organisation.vatId')"><span class="number">{{ organisation.vatId }}</span></Fact>
            <Fact v-if="organisation.companyId" :label="t('organisation.companyId')"><span class="number">{{ organisation.companyId }}</span></Fact>
            <Fact v-if="organisation.address" :label="t('organisation.address')">{{ organisation.address }}</Fact>
            <Fact v-if="organisation.contactPerson" :label="t('organisation.contact')">
              <RouterLink :to="path(personPath(organisation.contactPerson))">{{ organisation.contactPerson.name }}</RouterLink>
            </Fact>
          </dl>
        </FactGroup>
      </FactPanel>

      <div class="main">
        <p v-if="!organisation.people.length && !organisation.episodes.length && !organisation.events.length" class="note">
          {{ t('organisation.nothing') }}
        </p>

        <section aria-labelledby="events">
          <div class="heading">
            <h2 id="events">{{ t('organisation.events.title') }}</h2>
            <span class="count">{{ organisation.events.length }}</span>
            <button type="button" class="text-button more" :aria-expanded="addingEvent" aria-controls="add-event"
                    @click="addingEvent = !addingEvent">{{ addingEvent ? t('common.cancel') : t('events.team.add') }}</button>
          </div>
          <EventForm v-if="addingEvent" id="add-event" :organisation="organisation" @saved="eventAdded" @close="addingEvent = false"/>
          <p v-if="!organisation.events.length && !addingEvent" class="note">{{ t('organisation.events.none') }}</p>
          <ul v-if="organisation.events.length" class="events">
            <EventRow v-for="event in events" :key="event.id" :event="event" manage @changed="eventChanged"/>
          </ul>
          <button v-if="organisation.events.length > EVENTS_SHOWN" type="button" class="text-button all" :aria-expanded="eventsOpen"
                  @click="eventsOpen = !eventsOpen">
            {{ eventsOpen ? t('common.showLess') : t('common.showAll') }}
          </button>
        </section>

        <section v-for="list in workLists" :key="list.id" :aria-labelledby="list.id">
          <div class="heading">
            <h2 :id="list.id">{{ list.title }}</h2>
            <span class="count">{{ list.works.length }}</span>
          </div>
          <ul class="people">
            <li v-for="work in list.works" :key="work.id" class="row">
              <PersonAvatar :image="work.person.avatar" :name="work.person.name" :size="40"/>
              <div>
                <RouterLink class="name" :to="path(personPath(work.person))">{{ work.person.name }}</RouterLink>
                <p v-if="work.title || workPeriod(work, t, dateLocale)" class="line">
                  {{ [work.title, workPeriod(work, t, dateLocale)].filter(Boolean).join(' · ') }}
                </p>
              </div>
            </li>
          </ul>
        </section>

        <section v-if="organisation.episodes.length" aria-labelledby="supported">
          <div class="heading">
            <h2 id="supported">{{ t('organisation.supported') }}</h2>
            <span class="count">{{ organisation.episodes.length }}</span>
          </div>
          <ul class="episodes">
            <EpisodeRow v-for="episode in organisation.episodes" :key="episode.id" :episode="episode" thumbnail/>
          </ul>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// Its logo beside what it is
.organisation {
  display: flex;
  align-items: center;
  gap: 20px;
}

.people {
  padding: 0;
  list-style: none;
}

.row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}

.name {
  font-weight: 600;
  color: var(--ink);

  &:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}

.line {
  margin-top: 2px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

.number {
  font-family: var(--font-mono);
}

// The team's, under its name
.manage {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;

  .confirm {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    font-size: var(--text-small);
    line-height: 20px;
  }
}

.error {
  flex-basis: 100%;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

.events {
  padding: 0;
  list-style: none;
}

.all {
  margin-top: 12px;
}

// How a link was last scraped, under it
.scrape {
  display: block;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);

  &.failed {
    color: var(--warning);
  }
}

.panel-note {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

.refresh {
  display: grid;
  justify-items: start;
  gap: 6px;
}

dd a {
  @include page.link;
}

@media (max-width: 640px) {
  .organisation {
    gap: 14px;
  }
}
</style>
