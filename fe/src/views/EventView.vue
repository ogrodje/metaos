<script setup lang="ts">
import { faArrowUpRightFromSquare, faLocationDot } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import MeetupBadge from '@/components/MeetupBadge.vue'
import NotFound from '@/components/NotFound.vue'
import OrganisationLogo from '@/components/OrganisationLogo.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { EVENT_PAGE, type EventDetails, eventPath, eventSchedule, eventTime, isPast, mapUrl, paragraphs, placeLine } from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { organisationPath } from '@/organisations'
import { shorten, usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { useLocale } from '@/useLocale'

// An event's own page, anyone's, a past one's too, unless it's hidden (the team's then): when and where it is, where
// its organiser announces it, its description, and more from its organiser. The server renders it, as anyone sees it.
const auth = useAuthStore()
const route = useRoute()
const { t, dateLocale, path } = useLocale()

// The page is for the event in the path when it opens: going to another event opens another page
const id = String(route.params.id)

// Undefined while it loads; null when there's no such event, or none the user may see
const event = ref<EventDetails | null>()
const error = ref<string>()
// When it was loaded, which tells past events from upcoming ones, the same in the browser as on the server
const now = ref(0)

interface EventPage { event: EventDetails | null, now: number }

async function fetchEvent(): Promise<EventPage> {
  const { event } = await graphql<{ event: EventDetails | null }>(EVENT_PAGE, { id })
  return { event, now: Date.now() }
}

function show(page: EventPage) {
  event.value = keepUnchanged(event.value, page.event)
  now.value = page.now
}

let loads = 0

async function loadEvent(quietly = false) {
  const load = ++loads
  try {
    const page = await fetchEvent()
    if (load !== loads) return
    show(page)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e, t)
  }
}

const first = await useFirstLoad(`event:${id}`, fetchEvent)
if (first.data) show(first.data)
error.value = first.error

// Again on the team, who also see a hidden one, and once they're not
watch(() => auth.team, () => {
  if (event.value === null) event.value = undefined
  void loadEvent()
})
useLiveRefresh(() => loadEvent(true))

usePageTitle(() => event.value?.title)
usePageMeta(() => {
  const shown = event.value
  if (!shown) return {}
  const about = [eventTime(shown, dateLocale.value), shown.meetup?.name, placeLine(shown.place)].filter(Boolean).join(' · ')
  return { description: shorten(shown.description ? `${about}. ${shown.description.replace(/\s+/g, ' ')}` : about) }
})

const schedule = computed(() => (event.value ? eventSchedule(event.value, dateLocale.value) : null))
const days = computed(() => schedule.value && [schedule.value.startDay, schedule.value.endDay].filter(Boolean).join(' – '))
const hours = computed(() => {
  const s = schedule.value
  if (!s || (!s.startTime && !s.endTime)) return null
  // Over days, each time with its day
  if (s.endDay) {
    return [s.startTime && t('event.startsAt', { time: s.startTime }), s.endTime && t('event.endsAt', { time: s.endTime })]
      .filter(Boolean).join(', ')
  }
  return [s.startTime, s.endTime].filter(Boolean).join(' – ')
})

const place = computed(() => event.value?.place ?? (event.value ? { name: event.value.locationName, address: event.value.locationAddress, fromOrganisation: false } : null))
const where = computed(() => placeLine(place.value))
const map = computed(() => mapUrl(place.value))
const over = computed(() => !!event.value && isPast(event.value, now.value))
const text = computed(() => paragraphs(event.value?.description))

// Who it's by: their logo, what they are, and where they're online, as SocialLinks shows a person's
const organiser = computed(() => event.value?.organisation)
const organiserLinks = computed(() => organiser.value && {
  name: organiser.value.name,
  homepageUrl: organiser.value.websiteUrl,
  linkedinUrl: organiser.value.linkedinUrl,
  twitterUrl: organiser.value.twitterUrl,
  instagramUrl: organiser.value.instagramUrl,
  facebookUrl: organiser.value.facebookUrl,
})
const organiserOnline = computed(() => !!organiserLinks.value && Object.entries(organiserLinks.value)
  .some(([key, url]) => key !== 'name' && !!url))
</script>

<template>
  <main class="content">
    <LoadError v-if="error" :what="t('event.what')" :error="error" @retry="loadEvent()"/>
    <!-- None so far, while it's checked whether the user is on the team, who may see a hidden one -->
    <LoadingRows v-else-if="event === undefined || (event === null && auth.likelyTeam && !auth.ready)" :rows="6"
                 :label="t('event.loading')"/>
    <NotFound v-else-if="event === null" :title="t('event.notFound.title')">{{ t('event.notFound.text') }}</NotFound>

    <div v-else class="workspace">
      <div class="lead">
        <header class="title">
          <p class="back"><RouterLink :to="path('/dogodki')">{{ t('event.allEvents') }}</RouterLink></p>
          <h1>{{ event.title }}</h1>
          <p class="meta">
            <MeetupBadge v-if="event.meetup" :meetup="event.meetup"
                         :to="auth.team ? path(organisationPath(event.organisation)) : undefined"/>
            <span v-if="event.promoted" class="tag">{{ t('events.promoted') }}</span>
            <span v-if="over" class="tag quiet">{{ t('event.past') }}</span>
            <span v-if="event.hidden" class="tag quiet">{{ t('events.team.hidden') }}</span>
          </p>
        </header>
      </div>

      <FactPanel :label="t('event.about', { title: event.title })" tall>
        <FactGroup>
          <dl>
            <Fact :label="t('event.when')">
              <time :datetime="event.start">{{ days }}</time>
              <span v-if="hours" class="line">{{ hours }}</span>
            </Fact>
            <Fact v-if="where" :label="t('event.where')">
              <a v-if="map" :href="map" target="_blank" rel="noopener" :aria-label="t('events.onMap', { place: where })">
                <FontAwesomeIcon class="icon" :icon="faLocationDot"/>{{ where }}
              </a>
              <span v-else>{{ where }}</span>
              <span v-if="place?.fromOrganisation" class="line aside">{{ t('event.usualVenue') }}</span>
            </Fact>
          </dl>
          <a v-if="event.url" class="button primary announced" :href="event.url" target="_blank" rel="noopener">
            {{ t('event.announced') }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/><span class="visually-hidden"> {{ t('events.newTab') }}</span>
          </a>
        </FactGroup>

        <FactGroup v-if="organiser" :title="t('event.organiser')">
          <div class="organiser">
            <OrganisationLogo :image="organiser.logo" :name="organiser.name" :size="40"/>
            <RouterLink v-if="auth.team" class="name" :to="path(organisationPath(organiser))">{{ organiser.name }}</RouterLink>
            <a v-else-if="organiser.websiteUrl" class="name" :href="organiser.websiteUrl" target="_blank" rel="noopener">{{ organiser.name }}</a>
            <span v-else class="name">{{ organiser.name }}</span>
          </div>
          <p v-if="organiser.description" class="organiser-about">{{ organiser.description }}</p>
          <span v-if="organiserOnline && organiserLinks" class="icons"><SocialLinks :person="organiserLinks"/></span>
        </FactGroup>

        <FactGroup v-if="event.moreFromOrganiser.length" :title="t('event.more')">
          <ul class="more">
            <li v-for="other in event.moreFromOrganiser" :key="other.id">
              <RouterLink :to="path(eventPath(other))">{{ other.title }}</RouterLink>
              <span class="aside">
                <time :datetime="other.start">{{ eventTime(other, dateLocale) }}</time>
                · {{ isPast(other, now) ? t('event.past') : t('event.upcoming') }}
              </span>
            </li>
          </ul>
        </FactGroup>
      </FactPanel>

      <div class="main">
        <section aria-labelledby="description">
          <h2 id="description" class="visually-hidden">{{ t('event.description') }}</h2>
          <div v-if="text.length" class="description">
            <p v-for="(paragraph, i) in text" :key="i">{{ paragraph }}</p>
          </div>
          <p v-else class="note">{{ t('event.noDescription') }}</p>
        </section>
        <p class="disclaimer">{{ t('events.disclaimer') }}</p>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.back a {
  @include page.link;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.title h1 {
  text-wrap: pretty;
}

.tag {
  padding: 0 6px;
  border-radius: var(--radius-xs);
  background: var(--mark);
  font-size: var(--text-micro);
  line-height: 18px;
  font-weight: 600;
  color: var(--accent-text);
  white-space: nowrap;

  &.quiet {
    background: var(--hover);
    color: var(--secondary);
  }
}

dl {
  display: grid;
  gap: 8px;
}

dd a, .more a, a.name {
  @include page.link;
}

.line {
  display: block;
}

.aside {
  color: var(--muted);
}

.icon {
  width: 11px;
  height: 11px;
  margin-right: 6px;
  color: var(--muted);
  vertical-align: -1px;
}

.announced {
  justify-self: start;

  .external {
    width: 10px;
    height: 10px;
    margin-left: 8px;
  }
}

.more {
  display: grid;
  gap: 10px;
  padding: 0;
  list-style: none;

  li {
    display: grid;
    gap: 2px;
  }
}

// Its logo beside its name; what it is under them, its first lines, all of it in the page for search engines
.organiser {
  display: flex;
  gap: 12px;
  align-items: center;

  .name {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
}

.organiser-about {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
  overflow: hidden;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.icons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

// Running text, at its measure, its line breaks as its organiser wrote them
.description {
  display: grid;
  gap: 14px;
  max-width: 680px;
  font-size: var(--text-body);
  line-height: 26px;
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.note {
  color: var(--secondary);
}

.disclaimer {
  max-width: 680px;
  padding-top: 16px;
  border-top: 1px solid var(--rule);
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}
</style>
