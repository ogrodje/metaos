<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { type Account, ACCOUNT_FIELDS } from '@/accounts'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeCard from '@/components/EpisodeCard.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NotFound from '@/components/NotFound.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import PersonSignIns, { type MyAccount } from '@/components/PersonSignIns.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import ViewToggle, { type ListView } from '@/components/ViewToggle.vue'
import { type Episode, EPISODE_FIELDS, episodePath, type Role, roleName, THUMBNAIL_FIELDS } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { IMAGE_FIELDS } from '@/images'
import { seasonOf, seasonTitle } from '@/lists'
import { type Person, rolesOf, socialLinks } from '@/people'
import { useQueryState } from '@/query'
import { personData, shorten, usePageMeta, useSiteUrl } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'

/** A person with everything about them, for their page: their episodes whole, those they recommended, and their sign-ins */
interface PersonDetails extends Omit<Person, 'episodes' | 'accounts'> {
  episodes: { episode: Episode, roles: Role[] }[]
  recommended: Episode[]
  accounts: Account[]
}

const auth = useAuthStore()
const route = useRoute()
const siteUrl = useSiteUrl()

// The page is for the person in the path when it opens: going to another person opens another page
const id = String(route.params.id)

// Undefined while it loads; null when there's no such person, or none the user may see
const person = ref<PersonDetails | null>()
const error = ref<string>()
// Your account, which may say this person is you, and whether you may link accounts to people
const me = ref<MyAccount | null>()
const canLinkAccounts = ref(false)

interface PersonPage {
  person: PersonDetails | null
  me: { account: MyAccount | null, canLinkAccounts: boolean } | null
}

// Everything about them, and your account; for anyone not on the team, only their released episodes, and none they
// recommended nor their sign-ins
async function fetchPerson(id: string) {
  return graphql<PersonPage>(`query ($id: ID!) {
    person(id: $id) {
      id name bio homepageUrl linkedinUrl twitterUrl instagramUrl facebookUrl avatar { ${IMAGE_FIELDS} }
      episodes { episode { ${EPISODE_FIELDS} ${THUMBNAIL_FIELDS} } roles }
      recommended { ${EPISODE_FIELDS} }
      accounts { ${ACCOUNT_FIELDS} }
    }
    me { account { id username name email person { id } } canLinkAccounts }
  }`, { id })
}

function show(data: PersonPage) {
  person.value = keepUnchanged(person.value, data.person)
  me.value = keepUnchanged(me.value, data.me?.account ?? null)
  canLinkAccounts.value = data.me?.canLinkAccounts === true
}

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Quietly when MetaOS's data changes: what's shown stays until the new is there, and stays if it doesn't come
async function loadPerson(id: string, quietly = false) {
  const load = ++loads
  try {
    const data = await fetchPerson(id)
    if (load !== loads) return
    show(data)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// First as anyone may see them, as the server rendered it
const first = await useFirstLoad(`person:${id}`, () => fetchPerson(id))
if (first.data) show(first.data)
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

// After a link or an unlink, as the Discussion does after a comment
function reloadQuietly() {
  loadPerson(id, true).catch((e) => console.error('The person did not load again', e))
}

// In the order an episode's people are listed
const ROLES: Role[] = ['HOST', 'CO_HOST', 'GUEST', 'PRODUCER', 'CONSULTANT', 'DESIGNER', 'CANDIDATE']

// How many episodes they're on as each role, hosts first: also which of them are shown
const roleCounts = computed(() => ROLES.flatMap((role) => {
  const count = (person.value?.episodes ?? []).filter((on) => on.roles.includes(role)).length
  return count ? [{ role, count }] : []
}))
const role = useQueryState<Role | 'all'>('role', 'all', ['all', ...ROLES])
function pick(picked: Role) {
  role.value = role.value === picked ? 'all' : picked
}

// Their episodes, those of the role picked, each with what they are on it
const theirs = computed(() => (person.value?.episodes ?? [])
  .filter((on) => role.value === 'all' || on.roles.includes(role.value))
  .map((on) => ({ episode: on.episode, as: on.roles.map(roleName).join(', ') })))

// Being made, and ideas, the team's: first, as that's what's next
const comingUp = computed(() => theirs.value.filter(({ episode }) => episode.status !== 'PUBLISHED'))
const released = computed(() => theirs.value.filter(({ episode }) => episode.status === 'PUBLISHED'))

// Released ones by season, as the Episodes page lists them
const bySeason = computed(() => {
  const groups = new Map<string, typeof released.value>()
  for (const on of released.value) {
    const title = seasonTitle(seasonOf(on.episode))
    groups.set(title, [...(groups.get(title) ?? []), on])
  }
  return [...groups].map(([title, episodes]) => ({ title, episodes }))
})

const view = useQueryState<ListView>('view', 'list', ['list', 'grid'])

// Where it began: their first released episode, the oldest
const firstOn = computed(() => released.value.length ? (person.value?.episodes ?? [])
  .filter((on) => on.episode.status === 'PUBLISHED').at(-1)?.episode : undefined)

// What they recommended: the first few, until all are asked for
const RECOMMENDED = 5
const allRecommended = ref(false)
const recommended = computed(() => {
  const all = person.value?.recommended ?? []
  return allRecommended.value ? all : all.slice(0, RECOMMENDED)
})

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
        <PersonAvatar :image="person.avatar" :name="person.name" :size="88"/>
        <div class="title">
          <p class="kicker">{{ person.episodes.length ? rolesOf(person) : 'Person' }}</p>
          <h1>{{ person.name }}</h1>
          <p v-if="person.bio" class="subtitle">{{ person.bio }}</p>
        </div>
      </header>

      <div class="workspace">
        <FactPanel :label="`About ${person.name}`">
          <FactGroup v-if="roleCounts.length || person.recommended.length" title="On episodes" :count="person.episodes.length">
            <!-- Each also shows only those -->
            <div class="roles" role="group" aria-label="Show their episodes as">
              <button v-for="{ role: r, count } in roleCounts" :key="r" type="button" class="role" :aria-pressed="role === r" @click="pick(r)">
                <b>{{ count }}</b><span>as {{ roleName(r) }}</span>
              </button>
              <a v-if="person.recommended.length" class="role" href="#recommended">
                <b>{{ person.recommended.length }}</b><span>recommended</span>
              </a>
            </div>
          </FactGroup>
          <FactGroup v-if="hasLinks || firstOn">
            <dl>
              <Fact v-if="hasLinks" label="Online"><span class="icons"><SocialLinks :person="person"/></span></Fact>
              <Fact v-if="person.homepageUrl" label="Website">
                <a :href="person.homepageUrl">{{ person.homepageUrl.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '') }}</a>
              </Fact>
              <Fact v-if="firstOn" label="First on">
                <RouterLink :to="episodePath(firstOn)">{{ firstOn.code ?? firstOn.title }}</RouterLink> <span class="aside" lang="sl">{{ firstOn.title }}</span>
              </Fact>
            </dl>
          </FactGroup>
        </FactPanel>

        <div class="main">
          <p v-if="!person.episodes.length && !person.recommended.length" class="note">Not on any episode yet.</p>

          <section v-if="comingUp.length" aria-labelledby="coming-up">
            <div class="heading">
              <h2 id="coming-up">Coming up</h2>
              <span class="count">{{ comingUp.length }}</span>
            </div>
            <ul class="episodes">
              <EpisodeRow v-for="on in comingUp" :key="on.episode.id" :episode="on.episode" :role="on.as"/>
            </ul>
          </section>

          <section v-if="released.length" aria-labelledby="released">
            <div class="heading">
              <h2 id="released">Released</h2>
              <span class="count">{{ released.length }}</span>
              <ViewToggle v-model="view" class="view"/>
            </div>
            <ul v-if="view === 'grid'" class="episode-cards">
              <EpisodeCard v-for="on in released" :key="on.episode.id" :episode="on.episode" :role="`as ${on.as}`"/>
            </ul>
            <template v-for="group in bySeason" v-else :key="group.title">
              <h3 class="subheading">{{ group.title }} <span class="count">{{ group.episodes.length }}</span></h3>
              <ul class="episodes">
                <EpisodeRow v-for="on in group.episodes" :key="on.episode.id" :episode="on.episode" :role="on.as" thumbnail/>
              </ul>
            </template>
          </section>

          <section v-if="person.recommended.length" id="recommended" aria-labelledby="recommended-heading">
            <div class="heading">
              <h2 id="recommended-heading">Recommended</h2>
              <span class="count">{{ person.recommended.length }}</span>
              <button v-if="person.recommended.length > RECOMMENDED" type="button" class="more" :aria-expanded="allRecommended"
                      @click="allRecommended = !allRecommended">
                {{ allRecommended ? 'Show the first few' : `Show all ${person.recommended.length}` }}
              </button>
            </div>
            <ul class="episodes">
              <EpisodeRow v-for="episode in recommended" :key="episode.id" :episode="episode"/>
            </ul>
          </section>

          <PersonSignIns :person="person" :accounts="person.accounts" :me="me" :can-link="canLinkAccounts" @changed="reloadQuietly"/>
        </div>
      </div>
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

// How many episodes as what, each a button that shows only those
.roles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.role {
  display: grid;
  padding: 6px 10px;
  text-align: left;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 8px;
  cursor: pointer;

  b {
    font-size: 17px;
    line-height: 24px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  span {
    font-size: 11.5px;
    line-height: 16px;
    color: var(--muted);
  }

  &:hover {
    border-color: #A3A3A3;
  }

  &[aria-pressed="true"] {
    border-color: var(--ink);
    box-shadow: inset 0 0 0 1px var(--ink);
  }
}

.view {
  margin-left: auto;
  align-self: center;
}

.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

.aside {
  color: var(--muted);
}

dd a {
  @include page.link;
}

@media (max-width: 640px) {
  .person {
    gap: 14px;
  }
}
</style>
