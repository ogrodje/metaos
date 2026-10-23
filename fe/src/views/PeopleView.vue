<script setup lang="ts">
import { ref, watch } from 'vue'
import { type Account, ACCOUNT_FIELDS } from '@/accounts'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import PersonItem from '@/components/PersonItem.vue'
import UnlinkedAccounts from '@/components/UnlinkedAccounts.vue'
import { errorMessage, graphql } from '@/graphql'
import { type Person, PERSON_FIELDS } from '@/people'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// Everyone, by name, for signed-in users; for everyone else those on the released episodes
const people = ref<Person[]>()
const error = ref<string>()

// Loads again when the user signs in or out, as signed in there's more, and quietly when MetaOS's data changes, when
// who's shown stays if they don't come; only the latest load counts
let loads = 0

async function loadPeople(quietly = false) {
  const load = ++loads
  try {
    const data = await graphql<{ people: Person[] }>(`{ people { ${PERSON_FIELDS} } }`)
    if (load !== loads) return
    people.value = keepUnchanged(people.value, data.people)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// The sign-ins nobody's linked to, which is yours, and whether you may link them, for signed-in users: asked apart
// from the people, which everyone sees
const unlinked = ref<Account[]>()
const myAccount = ref<string>()
const canLinkAccounts = ref(false)
const unlinkedError = ref<string>()
let unlinkedLoads = 0

async function loadUnlinked(quietly = false) {
  const load = ++unlinkedLoads
  if (!auth.authenticated) {
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

watch(() => auth.authenticated, () => void loadAll(), { immediate: true })
useLiveRefresh(() => loadAll(true))
</script>

<template>
  <main class="content">
    <p v-if="auth.ready && !auth.authenticated" class="note">
      These are the people on the released episodes. Signed in, you see everyone, also on what's being made.
    </p>

    <p v-if="error" class="note">The people didn't load: {{ error }}</p>
    <p v-else-if="!people" class="note">Loading…</p>
    <template v-else>
      <!-- For signed-in users, when there are any: what's left to do comes first -->
      <p v-if="unlinkedError" class="note">The sign-ins nobody's linked to didn't load: {{ unlinkedError }}</p>
      <UnlinkedAccounts v-else-if="unlinked" :accounts="unlinked" :people="people" :me="myAccount"
                        :can-link="canLinkAccounts" @changed="reloadQuietly"/>

      <section aria-labelledby="people">
        <div class="heading">
          <h2 id="people">People</h2>
          <span class="count">{{ people.length }}</span>
        </div>
        <p v-if="!people.length" class="note">No one yet.</p>
        <ul v-else class="people">
          <PersonItem v-for="person in people" :key="person.id" :person="person"/>
        </ul>
      </section>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.people {
  padding: 0;
  list-style: none;
}

</style>
