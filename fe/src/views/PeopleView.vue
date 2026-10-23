<script setup lang="ts">
import { ref, watch } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import PersonItem from '@/components/PersonItem.vue'
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

watch(() => auth.team, () => void loadPeople())
useLiveRefresh(() => loadPeople(true))

usePageMeta(() => ({ description: "The hosts and guests of Ogrodje's podcast, who they are and where they're online." }))
</script>

<template>
  <main class="content">
    <p v-if="auth.ready && !auth.authenticated" class="note">
      These are the people on the released episodes. Signed in, you see everyone, also on what's being made.
    </p>

    <section aria-labelledby="people">
      <div class="heading">
        <h1 id="people">People</h1>
        <span v-if="people" class="count">{{ people.length }}</span>
      </div>
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
