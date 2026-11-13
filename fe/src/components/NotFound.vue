<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { faMagnifyingGlass } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { setResponseStatus, useHead } from '#imports'
import { searchPath } from '@/search'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { useLocale } from '@/useLocale'

// What isn't there, e.g. a page, an episode or a person, and where to go instead: on the team, a search for it first,
// as it may have moved, e.g. to another code
const props = defineProps<{
  title: string
  // What to search for, e.g. the code that wasn't found
  search?: string
}>()

const auth = useAuthStore()
const { t, path } = useLocale()

usePageTitle(() => props.title)

// A 404 from the server, for search engines and link previews, which leave it out; the page still loads in the
// browser, where the team may find what anyone else doesn't, e.g. an episode that isn't released
setResponseStatus(404)
useHead({ meta: [{ name: 'robots', content: 'noindex' }] })

const places = [
  { path: '/', title: 'nav.home' },
  { path: '/episodes', title: 'nav.episodes' },
  { path: '/people', title: 'nav.people' },
  { path: '/dogodki', title: 'nav.events' },
]

// The search, in the page's language
function searchFor(text: string) {
  const to = searchPath(text)
  return { ...to, path: path(to.path) }
}
</script>

<template>
  <section class="not-found" aria-labelledby="not-found">
    <h1 id="not-found">{{ title }}</h1>
    <p><slot/></p>
    <div class="actions">
      <RouterLink v-if="auth.team && search" class="button" :to="searchFor(search)">
        <FontAwesomeIcon :icon="faMagnifyingGlass"/>{{ t('ui.searchFor', { search }) }}
      </RouterLink>
      <RouterLink v-for="place in places" :key="place.path" class="button" :to="path(place.path)">{{ t(place.title) }}</RouterLink>
    </div>
  </section>
</template>

<style scoped lang="scss">

.not-found {
  display: flex;
  flex-direction: column;
  gap: 8px;

  h1 {
    font-size: var(--text-page);
    line-height: 34px;
    font-weight: 600;
    letter-spacing: -0.02em;
  }

  p {
    max-width: 70ch;
    color: var(--secondary);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
}

</style>
