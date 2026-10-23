<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NotFound from '@/components/NotFound.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { type Episode, EPISODE_FIELDS, type Role, roleName } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { IMAGE_FIELDS } from '@/images'
import { type Person, rolesOf, socialLinks } from '@/people'
import { personData, shorten, usePageMeta, useSiteUrl } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'

/** A person with everything about them, for their page: their episodes whole, and those they recommended */
interface PersonDetails extends Omit<Person, 'episodes'> {
  episodes: { episode: Episode, roles: Role[] }[]
  recommended: Episode[]
}

const auth = useAuthStore()
const route = useRoute()
const siteUrl = useSiteUrl()

// The page is for the person in the path when it opens: going to another person opens another page
const id = String(route.params.id)

// Undefined while it loads; null when there's no such person, or none the user may see
const person = ref<PersonDetails | null>()
const error = ref<string>()

// Everything about them; for anyone not on the team, only their released episodes, and none they recommended
async function fetchPerson(id: string) {
  return (await graphql<{ person: PersonDetails | null }>(`query ($id: ID!) {
    person(id: $id) {
      id name bio homepageUrl linkedinUrl twitterUrl instagramUrl facebookUrl avatar { ${IMAGE_FIELDS} }
      episodes { episode { ${EPISODE_FIELDS} } roles }
      recommended { ${EPISODE_FIELDS} }
    }
  }`, { id })).person
}

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Quietly when MetaOS's data changes: what's shown stays until the new is there, and stays if it doesn't come
async function loadPerson(id: string, quietly = false) {
  const load = ++loads
  try {
    const loaded = await fetchPerson(id)
    if (load !== loads) return
    person.value = keepUnchanged(person.value, loaded)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// First as anyone may see them, as the server rendered it
const first = await useFirstLoad(`person:${id}`, () => fetchPerson(id))
person.value = first.data
error.value = first.error

// Again when the user signs in or out: on the team, with all of their episodes, also of someone who isn't on a released
// one, who was none so far, and loads until it's known
watch(() => auth.team, () => {
  if (person.value === null) person.value = undefined
  void loadPerson(id)
})
useLiveRefresh(() => loadPerson(id, true))

usePageTitle(() => person.value?.name)

// For search engines and link previews: who they are and their picture
usePageMeta(() => {
  const shown = person.value
  if (!shown) return {}
  // E.g. Engineer @ Somewhere. Host, guest on Ogrodje.
  const about = [shown.bio?.replace(/[\s.]+$/, ''), shown.episodes.length ? `${rolesOf(shown)} on Ogrodje` : null]
    .filter(Boolean)
  return {
    description: about.length ? shorten(`${about.join('. ')}.`) : null,
    image: shown.avatar,
    structured: personData(shown, siteUrl),
  }
})

// In the order an episode's people are listed
const ROLES: Role[] = ['HOST', 'CO_HOST', 'GUEST', 'PRODUCER', 'CONSULTANT', 'DESIGNER', 'CANDIDATE']

// Their episodes by what they are on them, hosts first; one they're two things on is under both
const byRole = computed(() => ROLES.flatMap((role) => {
  const episodes = (person.value?.episodes ?? []).filter((on) => on.roles.includes(role)).map((on) => on.episode)
  return episodes.length ? [{ role, title: `As ${roleName(role)}`, episodes }] : []
}))

// Host on 61, guest on 2
const onEpisodes = computed(() => byRole.value
  .map(({ role, episodes }) => `${roleName(role)} on ${episodes.length}`)
  .join(', ')
  .replace(/^./, (first) => first.toUpperCase()))

const hasLinks = computed(() => (person.value ? socialLinks(person.value).length > 0 : false))
</script>

<template>
  <main class="content">
    <LoadError v-if="error" what="The person" :error="error" @retry="loadPerson(id)"/>
    <!-- None so far, while it's checked whether the user is on the team, who may see everyone -->
    <LoadingRows v-else-if="person === undefined || (person === null && auth.likelyMember && !auth.ready)" :rows="6"
                 label="Loading the person"/>
    <NotFound v-else-if="person === null" title="There's no such person">
      <template v-if="auth.team">
        They may have been deleted, or merged with someone else. The People page lists everyone.
      </template>
      <template v-else>
        They may not be on a released episode yet, or have been deleted. The People page lists everyone who is.
      </template>
    </NotFound>

    <template v-else>
      <header class="person">
        <PersonAvatar v-if="person.avatar" :image="person.avatar" :name="person.name" :size="72"/>
        <div class="title">
          <p class="kicker">{{ person.episodes.length ? rolesOf(person) : 'Person' }}</p>
          <h1>{{ person.name }}</h1>
          <p v-if="person.bio" class="subtitle">{{ person.bio }}</p>
        </div>
      </header>

      <section v-if="hasLinks || person.homepageUrl || byRole.length || person.recommended.length" aria-labelledby="details">
        <div class="heading"><h2 id="details">Details</h2></div>
        <dl class="details">
          <div v-if="person.homepageUrl">
            <dt>Website</dt>
            <dd><a class="link" :href="person.homepageUrl">{{ person.homepageUrl.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '') }}</a></dd>
          </div>
          <div v-if="hasLinks">
            <dt>Online</dt>
            <dd class="icons"><SocialLinks :person="person"/></dd>
          </div>
          <div v-if="byRole.length">
            <dt>Episodes</dt>
            <dd>{{ onEpisodes }}</dd>
          </div>
          <div v-if="person.recommended.length">
            <dt>Recommended</dt>
            <dd>{{ person.recommended.length }} {{ person.recommended.length === 1 ? 'episode' : 'episodes' }}</dd>
          </div>
        </dl>
      </section>

      <p v-if="!byRole.length && !person.recommended.length" class="note">Not on any episode yet.</p>

      <!-- Each as the other lists have it: a line while it's in the works, its summary once it's released -->
      <section v-for="group in byRole" :key="group.role" :aria-labelledby="`as-${group.role}`">
        <div class="heading">
          <h2 :id="`as-${group.role}`">{{ group.title }}</h2>
          <span class="count">{{ group.episodes.length }}</span>
        </div>
        <ul class="episodes">
          <template v-for="episode in group.episodes" :key="episode.id">
            <EpisodeItem v-if="episode.status === 'PUBLISHED'" :episode="episode"/>
            <EpisodeRow v-else :episode="episode"/>
          </template>
        </ul>
      </section>

      <section v-if="person.recommended.length" aria-labelledby="recommended">
        <div class="heading">
          <h2 id="recommended">Recommended</h2>
          <span class="count">{{ person.recommended.length }}</span>
        </div>
        <ul class="episodes">
          <template v-for="episode in person.recommended" :key="episode.id">
            <EpisodeItem v-if="episode.status === 'PUBLISHED'" :episode="episode"/>
            <EpisodeRow v-else :episode="episode"/>
          </template>
        </ul>
      </section>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// Their picture beside who they are
.person {
  display: flex;
  align-items: center;
  gap: 20px;
}

.link {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
