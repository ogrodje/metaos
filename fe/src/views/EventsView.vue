<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { I18nT } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EventForm from '@/components/EventForm.vue'
import EventRow from '@/components/EventRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PageHeader from '@/components/PageHeader.vue'
import { byWeek, EVENT_FIELDS, type ListedEvent, shortDay, TEAM_EVENTS, type TeamEvent, weekdayName, weekRange } from '@/events'
import { useEventsRefresh } from '@/eventsRefresh'
import { graphql } from '@/graphql'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'

// Slovenia's tech, developer and startup events that haven't ended yet, as MetaOS gathers them from organisations'
// links, as ogrodje.si's Dogodki page listed them: by week, then by day, in the page's language (the events themselves
// are as their organisers write them). Everyone sees the same, so the server renders it. Once the page knows it's the
// team's, it loads them again with the hidden ones and what's the team's, to hide, pick, add and refresh them.
const auth = useAuthStore()
const { t, dateLocale, path } = useLocale()
const events = ref<(ListedEvent | TeamEvent)[]>()
const error = ref<unknown>()

async function fetchEvents(): Promise<(ListedEvent | TeamEvent)[]> {
  if (!import.meta.server && auth.team) return (await graphql<{ events: TeamEvent[] }>(TEAM_EVENTS)).events
  return (await graphql<{ events: ListedEvent[] }>(`{ events { ${EVENT_FIELDS} } }`)).events
}

let loads = 0

async function loadEvents(quietly = false) {
  const load = ++loads
  try {
    const loaded = await fetchEvents()
    if (load !== loads) return
    events.value = keepUnchanged(events.value, loaded)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = e
  }
}

const first = await useFirstLoad('events', fetchEvents)
events.value = first.data
error.value = first.error

// As the scrapers find them and the team changes them
useLiveRefresh(() => loadEvents(true))
watch(() => auth.team, (team, was) => {
  if (team || was) void loadEvents(true).catch(() => undefined)
})

// The team's: refreshing every organisation's events, and adding one of their own
const { running: refreshing, starting: refreshStarting, error: refreshError, start: startRefresh } = useEventsRefresh(() => null, () => loadEvents(true).catch(() => undefined))
const adding = ref(false)

function changed(event: TeamEvent) {
  events.value = events.value?.map((each) => (each.id === event.id ? event : each))
}

function added() {
  void loadEvents(true).catch(() => undefined)
}

usePageMeta(() => ({
  description: t('events.description'),
}))

const weeks = computed(() => byWeek(events.value ?? []))
</script>

<template>
  <main class="content">
    <PageHeader :title="t('pages.events')" :count="events?.length">
      <I18nT keypath="events.intro" scope="global">
        <template #discord>
          <a class="link" href="https://discord.com/invite/xV2Xc4VX9W" target="_blank" rel="noopener">{{ t('events.discord') }}</a>
        </template>
        <template #channel><strong>#🗓-dogodki</strong></template>
      </I18nT>
      <template v-if="auth.team" #actions>
        <button type="button" class="button" :aria-disabled="refreshing || refreshStarting || undefined"
                @click="startRefresh()">
          {{ refreshing || refreshStarting ? t('events.team.refreshing') : t('events.team.refresh') }}
        </button>
        <button type="button" class="button" :class="{ primary: !adding }" :aria-expanded="adding" aria-controls="add-event"
                @click="adding = !adding">
          {{ adding ? t('common.cancel') : t('events.team.add') }}
        </button>
      </template>
    </PageHeader>

    <template v-if="auth.team">
      <p v-if="refreshing" class="status" role="status">{{ t('events.team.refreshNote') }}</p>
      <p v-if="refreshError" class="status error" role="alert">{{ t('events.team.notRefreshed', { error: refreshError }) }}</p>
      <EventForm v-if="adding" id="add-event" @saved="added" @close="adding = false"/>
    </template>

    <LoadError v-if="error" :what="t('events.what')" :error="error" @retry="loadEvents()"/>
    <LoadingRows v-else-if="!events" :rows="6" tall :label="t('events.loading')"/>
    <p v-else-if="!events.length" class="note">{{ t('events.none') }}</p>

    <template v-else>
      <section v-for="week in weeks" :key="week.monday" :aria-labelledby="`week-${week.monday}`">
        <div class="heading">
          <h2 :id="`week-${week.monday}`">{{ t('events.week', { range: weekRange(week.monday, dateLocale) }) }}</h2>
          <span class="count">{{ week.count }}</span>
        </div>
        <template v-for="day in week.days" :key="day.day">
          <h3 class="day">{{ weekdayName(day.day, dateLocale) }} <span class="date">{{ shortDay(day.day, dateLocale) }}</span></h3>
          <ul class="events">
            <EventRow v-for="event in day.events" :key="event.id" :event="event" :manage="auth.team" @changed="changed"/>
          </ul>
        </template>
      </section>
    </template>

    <footer class="disclaimer">
      <I18nT keypath="events.disclaimer" tag="p" scope="global">
        <template #not><strong>{{ t('events.not') }}</strong></template>
        <template #goo>
          <a class="link" href="https://github.com/ogrodje/goo" target="_blank" rel="noopener">Goo</a>
        </template>
      </I18nT>
      <I18nT keypath="events.api" tag="p" scope="global">
        <template #api>
          <RouterLink class="link" :to="path('/usage')">{{ t('events.apiLink') }}</RouterLink>
        </template>
        <template #query><code>events</code></template>
      </I18nT>
    </footer>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.link {
  @include page.link;
}

.events {
  padding: 0;
  list-style: none;
}

// A day's name over its events, with its date
.day {
  padding: 20px 0 8px;
  font-size: var(--text-body);
  line-height: 20px;
  font-weight: 600;

  .date {
    margin-left: 4px;
    font-weight: 400;
    color: var(--muted);
  }
}

.heading + .day {
  padding-top: 0;
}

.note {
  color: var(--secondary);
}

.status {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);

  &.error {
    color: var(--accent-text);
  }
}

.disclaimer {
  display: grid;
  gap: 8px;
  max-width: 680px;
  padding-top: 16px;
  border-top: 1px solid var(--rule);
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}
</style>
