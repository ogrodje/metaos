<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { type Account, ACCOUNT_FIELDS } from '@/accounts'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PersonItem from '@/components/PersonItem.vue'
import UnlinkedAccounts from '@/components/UnlinkedAccounts.vue'
import { errorMessage, graphql } from '@/graphql'
import { type Person, PERSON_FIELDS } from '@/people'
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
</script>

<template>
  <main class="content">
    <p v-if="auth.ready && !auth.authenticated" class="note">
      These are the people on the released episodes. Signed in, you see everyone, also on what's being made.
    </p>

    <!-- The page's name, then for signed-in users, when there are any, what's left to do, then everyone -->
    <div class="heading">
      <h1 id="people">People</h1>
      <span v-if="people" class="count">{{ people.length }}</span>
    </div>

    <template v-if="people">
      <p v-if="unlinkedError" class="note">The sign-ins nobody's linked to didn't load: {{ unlinkedError }}</p>
      <UnlinkedAccounts v-else-if="unlinked" :accounts="unlinked" :people="people" :me="myAccount"
                        :can-link="canLinkAccounts" @changed="reloadQuietly"/>
    </template>

    <section aria-labelledby="people">
      <LoadError v-if="error" what="The people" :error="error" @retry="loadPeople()"/>
      <LoadingRows v-else-if="!people" :rows="6" tall label="Loading the people"/>
      <p v-else-if="!people.length" class="note">No one yet.</p>
      <ul v-else class="people">
        <PersonItem v-for="person in people" :key="person.id" :person="person"/>
      </ul>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.people {
  padding: 0;
  list-style: none;
}

</style>
