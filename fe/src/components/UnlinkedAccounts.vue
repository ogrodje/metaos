<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { RouterLink } from 'vue-router'
import PersonPicker, { type Pickable } from '@/components/PersonPicker.vue'
import { type Account, accountName, LINK_ACCOUNT, unlinkedDetails } from '@/accounts'
import { errorMessage, graphql } from '@/graphql'
import { personPath } from '@/people'

// The sign-ins MetaOS has seen that nobody's linked to, for the People page. For the one user who may link accounts
// (canLinkAccounts), each has Link to…, which picks the person it is from those the page lists; the page loads its data
// again after each link (changed).
const props = defineProps<{
  // As the API has them
  accounts: Account[]
  people: Pickable[]
  // The signed-in user's account
  me?: string
  // Whether they may link accounts; everyone else sees them only
  canLink: boolean
}>()

const emit = defineEmits<{
  changed: []
}>()

const id = useId()

// Those linked here, as MetaOS answered, left out until the page's accounts change: its reload a moment later has
// them gone, and anything newer would too
const linked = ref<string[]>([])
watch(() => props.accounts, () => {
  linked.value = []
})

const shown = computed(() => props.accounts.filter((account) => !linked.value.includes(account.id)))

// The account whose person is being picked; one at a time
const picking = ref<string>()
const busy = ref(false)
const error = ref<{ account: string, text: string }>()
// What the last link did, until the next
const done = ref<{ account: string, person: { id: string, name: string } }>()

const heading = ref<HTMLHeadingElement>()

function linkButton(account: Account) {
  return `${id}-link-${account.id}`
}

function pick(account: Account) {
  error.value = undefined
  picking.value = account.id
}

function stopPicking(account: Account) {
  picking.value = undefined
  error.value = undefined
  void nextTick(() => document.getElementById(linkButton(account))?.focus())
}

async function link(account: Account, person: Pickable) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await graphql<{ account: Account }>(LINK_ACCOUNT, { id: account.id, person: person.id })
    linked.value = [...linked.value, account.id]
    done.value = { account: accountName(account), person: { id: person.id, name: person.name } }
    picking.value = undefined
    emit('changed')
    // Its row went with it: the section has the focus instead, where it is
    heading.value?.focus({ preventScroll: true })
  } catch (e) {
    error.value = { account: account.id, text: `It wasn't linked: ${errorMessage(e)}` }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section v-if="shown.length || done" :aria-labelledby="`${id}-heading`">
    <div class="heading">
      <h2 :id="`${id}-heading`" ref="heading" tabindex="-1">Not linked to anyone</h2>
      <span class="count">{{ shown.length }}</span>
    </div>
    <p v-if="canLink" class="note">
      Sign-ins MetaOS has seen that aren't anyone's yet. Link each to the person it is, and its comments and history
      get their name.
    </p>
    <p v-else class="note">
      Sign-ins MetaOS has seen that aren't anyone's yet. Oto links them to the people they are, and their comments and
      history then get those names.
    </p>

    <ul v-if="shown.length" class="sign-ins">
      <li v-for="account in shown" :key="account.id" class="sign-in">
        <div class="row">
          <p class="what">
            <span class="name">{{ accountName(account) }}</span>
            <span v-if="account.id === me" class="you">you</span>
            <span class="how">{{ unlinkedDetails(account) }}</span>
          </p>
          <button v-if="canLink && picking !== account.id" :id="linkButton(account)" type="button" class="small"
                  :aria-label="`Link ${accountName(account)} to a person`" @click="pick(account)">Link to…</button>
        </div>
        <PersonPicker v-if="picking === account.id" :people="people" :suggest="account.name"
                      :label="`Link ${accountName(account)} to`" :busy="busy" @pick="link(account, $event)"
                      @cancel="stopPicking(account)"/>
        <p v-if="error?.account === account.id" class="error" role="alert">{{ error.text }}</p>
      </li>
    </ul>

    <p class="done" role="status">
      <template v-if="done">
        {{ done.account }} is linked to <RouterLink class="link" :to="personPath(done.person)">{{ done.person.name }}</RouterLink>.
      </template>
    </p>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

// Focused only when the row it had the focus in is gone
h2:focus {
  outline: 0;
}

.heading + .note {
  margin: -6px 0 12px;
  max-width: 640px;
}

.sign-ins {
  padding: 0;
  list-style: none;
}

.sign-in {
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--icon-link);
}

.what {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: 8px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.name {
  font-weight: 500;
}

// The signed-in user's own
.you {
  font-size: 11.5px;
  line-height: 18px;
  font-weight: 600;
  color: var(--accent-text);
}

.how {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

// A small outlined button, as the design system's, in a row's height
.small {
  flex: none;
  min-height: var(--icon-link);
  padding: 0 12px;
  font-size: 13px;
  line-height: 20px;
  font-weight: 500;
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 6.4px;
  cursor: pointer;

  &:hover {
    border-color: #A3A3A3;
  }
}

.error {
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--accent-text);
}

.done {
  margin-top: 12px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);

  // Kept, empty, so screen readers say what comes into it
  &:empty {
    margin: 0;
  }

  .link {
    color: var(--accent-text);

    &:hover {
      text-decoration: underline;
    }
  }
}

button:focus-visible, a:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

</style>
