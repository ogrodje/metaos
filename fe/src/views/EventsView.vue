<script setup lang="ts">
import { computed, ref } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EventRow from '@/components/EventRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PageHeader from '@/components/PageHeader.vue'
import { byWeek, EVENT_FIELDS, type GooEvent, shortDay, weekdayName, weekRange } from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'

// Slovenia's tech, developer and startup events that haven't ended yet, from Goo, as ogrodje.si's Dogodki page listed
// them, and in Slovenian as it did: by week, then by day. Everyone sees the same, so the server renders it.
const events = ref<GooEvent[]>()
const error = ref<string>()

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
    error.value = errorMessage(e)
  }
}

const first = await useFirstLoad('events', fetchEvents)
events.value = first.data
error.value = first.error

// Goo doesn't tell MetaOS what changed, but a page from a cache, or one hidden for a while, loads them again
useLiveRefresh(() => loadEvents(true))

usePageMeta(() => ({
  description: 'Tehnološki, razvijalski in startup dogodki ter konference v Sloveniji, ki jih spremlja Ogrodje, po tednih.',
}))

const weeks = computed(() => byWeek(events.value ?? []))
</script>

<template>
  <main class="content" lang="sl">
    <PageHeader title="Dogodki in konference" :count="events?.length">
      Ogrodje spremlja preko 60 tehnoloških/razvijalskih/startup dogodkov/konferenc in organizacij. Na dogodke se lahko
      naročite tudi v našem <a class="link" href="https://discord.com/invite/xV2Xc4VX9W" target="_blank" rel="noopener">Discord
      strežniku</a>, kanal <strong>#🗓-dogodki</strong>.
    </PageHeader>

    <LoadError v-if="error" what="The events" :error="error" lang="en" @retry="loadEvents()"/>
    <LoadingRows v-else-if="!events" :rows="6" tall label="Nalagam dogodke"/>
    <p v-else-if="!events.length" class="note">Ta hip ni prihajajočih dogodkov.</p>

    <template v-else>
      <section v-for="week in weeks" :key="week.monday" :aria-labelledby="`week-${week.monday}`">
        <div class="heading">
          <h2 :id="`week-${week.monday}`">Teden / {{ weekRange(week.monday) }}</h2>
          <span class="count">{{ week.count }}</span>
        </div>
        <template v-for="day in week.days" :key="day.day">
          <h3 class="day">{{ weekdayName(day.day) }} <span class="date">{{ shortDay(day.day) }}</span></h3>
          <ul class="events">
            <EventRow v-for="event in day.events" :key="event.id" :event="event"/>
          </ul>
        </template>
      </section>
    </template>

    <footer class="disclaimer">
      <p>
        ⚠️ Sistem za zbiranje dogodkov in konferenc je v razvoju. Prosimo, preverite točnost podatkov na straneh
        organizatorjev. Ogrodje <strong>ni</strong> organizator. Dogodki in podatki o konferencah so zbrani iz javnih
        virov. Napake, želje in PR-je sprejemamo na
        <a class="link" href="https://github.com/ogrodje/goo" target="_blank" rel="noopener">github.com/ogrodje/goo</a>.
      </p>
      <p>
        Podatki o dogodkih so na voljo tudi v
        <a class="link" href="https://goo.ogrodje.si/docs/openapi" target="_blank" rel="noopener">strojni obliki prek API-ja</a>.
        Podatki se osvežijo vsakih 12 ur.
      </p>
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
