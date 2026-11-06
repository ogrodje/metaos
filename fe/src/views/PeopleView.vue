<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { type Account, ACCOUNT_FIELDS } from '@/accounts'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import FilterBar from '@/components/FilterBar.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import PageHeader from '@/components/PageHeader.vue'
import PersonCard from '@/components/PersonCard.vue'
import UnlinkedAccounts from '@/components/UnlinkedAccounts.vue'
import { errorMessage, graphql } from '@/graphql'
import { ALPHABET, type Kind, kindOf, letterOf, matches } from '@/lists'
import { type Person, PERSON_FIELDS } from '@/people'
import { useClearQuery, useQueryState } from '@/query'
import { usePageMeta } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// Everyone, by name, for the team; for everyone else those on the released episodes
const people = ref<Person[]>()
const error = ref<string>()

async function fetchPeople() {
  return (await graphql<{ people: Person[] }>(`{ people { ${PERSON_FIELDS} } }`)).people
}

// Loads again when the user signs in or out, as on the team there's more, and quietly when MetaOS's data changes, when
// who's shown stays if they don't come; only the latest load counts
let loads = 0

async function loadPeople(quietly = false) {
  const load = ++loads
  try {
    const loaded = await fetchPeople()
    if (load !== loads) return
    people.value = keepUnchanged(people.value, loaded)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// First those on the released episodes, as the server rendered them
const first = await useFirstLoad('people', fetchPeople)
people.value = first.data
error.value = first.error

// The sign-ins nobody's linked to, which is yours, and whether you may link them, for the team: asked apart from the
// people, which everyone sees, and only in the browser
const unlinked = ref<Account[]>()
const myAccount = ref<string>()
const canLinkAccounts = ref(false)
const unlinkedError = ref<string>()
let unlinkedLoads = 0

async function loadUnlinked(quietly = false) {
  const load = ++unlinkedLoads
  if (!auth.team) {
    unlinked.value = undefined
    unlinkedError.value = undefined
    return
  }
  try {
    const data = await graphql<{
      accounts: Account[]
      me: { account: { id: string } | null, canLinkAccounts: boolean } | null
    }>(`{ accounts(unlinked: true) { ${ACCOUNT_FIELDS} } me { account { id } canLinkAccounts } }`)
    if (load !== unlinkedLoads) return
    unlinked.value = keepUnchanged(unlinked.value, data.accounts)
    myAccount.value = data.me?.account?.id
    canLinkAccounts.value = data.me?.canLinkAccounts === true
    unlinkedError.value = undefined
  } catch (e) {
    if (load !== unlinkedLoads) return
    if (quietly) throw e
    unlinkedError.value = errorMessage(e)
  }
}

function loadAll(quietly = false) {
  return Promise.all([loadPeople(quietly), loadUnlinked(quietly)])
}

// After a link, as the Discussion does after a comment
function reloadQuietly() {
  loadAll(true).catch((error) => console.error('The people did not load again', error))
}

onMounted(() => {
  if (auth.team) void loadUnlinked()
})

// Again when the user signs in or out, as on the team there's more
watch(() => auth.team, () => void loadAll())
useLiveRefresh(() => loadAll(true))

usePageMeta(() => ({ description: "The hosts and guests of Ogrodje's podcast, who they are and where they're online." }))

// Who's shown, as the address says, e.g. ?show=team&q=sraka
const shown = useQueryState<'all' | Kind>('show', 'all', ['all', 'team', 'guest', 'candidate'])
const find = useQueryState('q', '')
const clear = useClearQuery()

const everyone = computed(() => people.value ?? [])
const found = computed(() => everyone.value.filter((person) => matches(`${person.name} ${person.bio ?? ''}`, find.value)))
const ofKind = (kind: Kind) => found.value.filter((person) => kindOf(person) === kind)

// Who's left once filtered, for how many of them there are, and whether anyone is
const filtering = computed(() => shown.value !== 'all' || find.value.trim() !== '')
const listed = computed(() => (shown.value === 'all' ? found.value : ofKind(shown.value)))

// The team by how many episodes they've been on, the most first; everyone else by name, as the API has them
const team = computed(() => ofKind('team').sort((a, b) => b.episodes.length - a.episodes.length))
const guests = computed(() => ofKind('guest'))
const candidates = computed(() => ofKind('candidate'))

const kinds = computed(() => [
  { value: 'all' as const, title: 'Everyone', count: everyone.value.length },
  { value: 'team' as const, title: 'The team', count: everyone.value.filter((p) => kindOf(p) === 'team').length },
  { value: 'guest' as const, title: 'Guests', count: everyone.value.filter((p) => kindOf(p) === 'guest').length },
  ...(everyone.value.some((p) => kindOf(p) === 'candidate')
    ? [{ value: 'candidate' as const, title: 'Candidates', count: everyone.value.filter((p) => kindOf(p) === 'candidate').length }]
    : []),
])

// The guests under their names' letters, for the index
const byLetter = computed(() => {
  const groups = new Map<string, Person[]>()
  for (const person of guests.value) {
    const letter = letterOf(person.name)
    groups.set(letter, [...(groups.get(letter) ?? []), person])
  }
  return [...groups].map(([letter, people]) => ({ letter, people }))
})
const letters = computed(() => new Set(byLetter.value.map((group) => group.letter)))

// Candidates, the team's, fold away unless they're what's shown
const candidatesOpen = ref(false)
</script>

<template>
  <main class="content">
    <PageHeader title="People" :count="people?.length" :shown="filtering && people ? listed.length : undefined">
      <template v-if="auth.team">Ogrodje's team, the guests on its episodes, and who could be one.</template>
      <template v-else-if="auth.ready && !auth.authenticated">
        The hosts, guests and everyone else on Ogrodje's released episodes. Signed in, you see everyone, also on what's
        being made.
      </template>
      <template v-else>The hosts, guests and everyone else on Ogrodje's released episodes.</template>
    </PageHeader>

    <FilterBar v-model:find="find" placeholder="Find by name or what they do">
      <ChipGroup v-model="shown" label="Show" :options="kinds"/>
      <template v-if="shown === 'all' || shown === 'guest'" #end>
        <nav class="index" aria-label="Guests by letter">
          <template v-for="letter in ALPHABET" :key="letter">
            <a v-if="letters.has(letter)" :href="`#letter-${letter}`">{{ letter }}</a>
            <span v-else aria-hidden="true">{{ letter }}</span>
          </template>
        </nav>
      </template>
    </FilterBar>

    <LoadError v-if="error" what="The people" :error="error" @retry="loadPeople()"/>
    <LoadingRows v-else-if="!people" :rows="6" tall label="Loading the people"/>
    <p v-else-if="!people.length" class="note">No one yet.</p>
    <NoMatch v-else-if="!listed.length" :find="find" @clear="clear(['show', 'q'])">No one matches</NoMatch>

    <template v-else>
      <section v-if="team.length && (shown === 'all' || shown === 'team')" aria-labelledby="the-team">
        <div class="heading">
          <h2 id="the-team">The team</h2>
          <span class="count">{{ team.length }}</span>
        </div>
        <ul class="team">
          <PersonCard v-for="person in team" :key="person.id" :person="person" team/>
        </ul>
      </section>

      <section v-if="guests.length && (shown === 'all' || shown === 'guest')" aria-labelledby="guests">
        <div class="heading">
          <h2 id="guests">Guests</h2>
          <span class="count">{{ guests.length }}</span>
        </div>
        <!-- On phones, where the filters' index doesn't fit, the letters with anyone, over the guests as they scroll -->
        <nav class="letters" aria-label="Guests by letter">
          <a v-for="group in byLetter" :key="group.letter" :href="`#letter-${group.letter}`">{{ group.letter }}</a>
        </nav>
        <div class="guests">
          <template v-for="group in byLetter" :key="group.letter">
            <h3 :id="`letter-${group.letter}`" class="letter">{{ group.letter }}</h3>
            <ul class="people">
              <PersonCard v-for="person in group.people" :key="person.id" :person="person"/>
            </ul>
          </template>
        </div>
      </section>

      <!-- The team's: who could be a guest, folded unless they're what's shown -->
      <section v-if="candidates.length && (shown === 'all' || shown === 'candidate')" aria-labelledby="candidates">
        <div class="heading">
          <h2 id="candidates">Candidates</h2>
          <span class="count">{{ candidates.length }}</span>
          <button v-if="shown === 'all'" type="button" class="text-button more" :aria-expanded="candidatesOpen" aria-describedby="candidates"
                  @click="candidatesOpen = !candidatesOpen">{{ candidatesOpen ? 'Fold' : `Show all ${candidates.length}` }}</button>
        </div>
        <ul v-if="candidatesOpen || shown === 'candidate'" class="people">
          <PersonCard v-for="person in candidates" :key="person.id" :person="person"/>
        </ul>
        <p v-else class="note">Who the team has in mind for an idea, as its guest.</p>
      </section>
    </template>

    <!-- For the team, the sign-ins nobody's linked to, which whoever links accounts links -->
    <template v-if="people">
      <p v-if="unlinkedError" class="note">The sign-ins nobody's linked to didn't load: {{ unlinkedError }}</p>
      <UnlinkedAccounts v-else-if="unlinked" :accounts="unlinked" :people="people" :me="myAccount"
                        :can-link="canLinkAccounts" @changed="reloadQuietly"/>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// The team: three a row in the frame, fewer as it narrows, in the guests' columns
.team {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: 32px;
  padding: 0;
  list-style: none;
}

// The guests under their letters, a line each, in three columns
.letter {
  padding: 16px 0 6px;
  font-size: var(--text-small);
  line-height: 20px;
  font-weight: 600;
  color: var(--muted);
  scroll-margin-top: 16px;

  &:first-child {
    padding-top: 0;
  }
}

.people {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: 32px;
  padding: 0;
  list-style: none;
}

// A–Z, the letters without anyone greyed
.index {
  display: flex;
  flex-wrap: wrap;
  font-size: var(--text-caption);
  line-height: 20px;
  font-weight: 500;

  a, span {
    padding: 2px 5px;
    border-radius: var(--radius-xs);
  }

  a {
    color: var(--secondary);

    &:hover {
      background: var(--hover);
    }
  }

  span {
    color: var(--faint);
  }
}

@media (max-width: 960px) {
  .team {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .people {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

// The phones' index, a row of letters that stays at the top while the guests scroll by, and scrolls sideways itself
.letters {
  display: none;
}

@media (max-width: 640px) {
  .team {
    grid-template-columns: minmax(0, 1fr);
  }

  .people {
    grid-template-columns: minmax(0, 1fr);
  }

  .index {
    display: none;
  }

  .letters {
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    margin: 0 -16px 8px;
    padding: 0 12px;
    overflow-x: auto;
    background: #FFFFFF;
    border-bottom: 1px solid var(--rule);
    scrollbar-width: none;

    a {
      display: grid;
      flex: none;
      place-items: center;
      min-width: 36px;
      height: 40px;
      font-size: var(--text-small);
      font-weight: 500;
      color: var(--secondary);
    }
  }

  // Past the index, which would cover it
  .letter {
    scroll-margin-top: 56px;
  }
}
</style>
