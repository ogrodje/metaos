<script setup lang="ts">
import { computed, ref } from 'vue'
import { I18nT } from 'vue-i18n'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EventRow from '@/components/EventRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PageHeader from '@/components/PageHeader.vue'
import { byWeek, EVENT_FIELDS, type GooEvent, shortDay, weekdayName, weekRange } from '@/events'
import { graphql } from '@/graphql'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useLocale } from '@/useLocale'

// Slovenia's tech, developer and startup events that haven't ended yet, from Goo, as ogrodje.si's Dogodki page listed
// them: by week, then by day, in the page's language (the events themselves are as Goo has them). Everyone sees the
// same, so the server renders it.
const { t, dateLocale } = useLocale()
const events = ref<GooEvent[]>()
const error = ref<unknown>()

async function fetchEvents() {
  return (await graphql<{ events: GooEvent[] }>(`{ events { ${EVENT_FIELDS} } }`)).events
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

// Goo doesn't tell MetaOS what changed, but a page from a cache, or one hidden for a while, loads them again
useLiveRefresh(() => loadEvents(true))

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
    </PageHeader>

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
            <EventRow v-for="event in day.events" :key="event.id" :event="event"/>
          </ul>
        </template>
      </section>
    </template>

    <footer class="disclaimer">
      <I18nT keypath="events.disclaimer" tag="p" scope="global">
        <template #not><strong>{{ t('events.not') }}</strong></template>
        <template #github>
          <a class="link" href="https://github.com/ogrodje/goo" target="_blank" rel="noopener">github.com/ogrodje/goo</a>
        </template>
      </I18nT>
      <I18nT keypath="events.api" tag="p" scope="global">
        <template #api>
          <a class="link" href="https://goo.ogrodje.si/docs/openapi" target="_blank" rel="noopener">{{ t('events.apiLink') }}</a>
        </template>
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
