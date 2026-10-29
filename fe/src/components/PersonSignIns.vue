<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { type Account, accountName, LINK_ACCOUNT, signInDetails, UNLINK_ACCOUNT } from '@/accounts'
import { errorMessage, graphql } from '@/graphql'

/** The signed-in user's own account, and the person it's linked to */
export interface MyAccount extends Pick<Account, 'id' | 'username' | 'name' | 'email'> {
  person: { id: string } | null
}

// A person's sign-ins: the accounts MetaOS links to them, and how and when each was. The one user who may link
// accounts (canLinkAccounts) also has Unlink, once it's been said here rather than in a dialog, and while their own
// account isn't anyone yet, This is me on the page of someone nobody signs in as. The page loads its data again after
// each write here (changed).
const props = defineProps<{
  person: { id: string, name: string }
  // As the API has them
  accounts: Account[]
  // Yours; null when MetaOS doesn't know it
  me: MyAccount | null | undefined
  // Whether you may link and unlink accounts; everyone else sees them only
  canLink: boolean
}>()

const emit = defineEmits<{
  changed: []
}>()

const id = useId()

// What was written here, as MetaOS answered, shown until the page's accounts change: its reload a moment later brings
// them, and anything newer would too
const added = ref<Account[]>([])
const removed = ref<string[]>([])
watch(() => props.accounts, () => {
  added.value = []
  removed.value = []
})

const shown = computed(() => [...props.accounts, ...added.value.filter((a) => !props.accounts.some((b) => b.id === a.id))]
  .filter((account) => !removed.value.includes(account.id)))

// This is me: for a user whose account is no one's yet, on the page of someone nobody signs in as
const claimable = computed(() => props.canLink && !!props.me && !props.me.person && !shown.value.length)

const confirming = ref<string>()
const busy = ref(false)
const error = ref<string>()

const heading = ref<HTMLHeadingElement>()

function focusById(element: string) {
  void nextTick(() => document.getElementById(element)?.focus())
}

// Cancel first, so a second press doesn't unlink it
function askToUnlink(account: Account) {
  error.value = undefined
  confirming.value = account.id
  focusById(`${id}-keep`)
}

function keep(account: Account) {
  confirming.value = undefined
  focusById(`${id}-unlink-${account.id}`)
}

async function unlink(account: Account) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await graphql(UNLINK_ACCOUNT, { id: account.id })
    removed.value = [...removed.value, account.id]
    confirming.value = undefined
    emit('changed')
    // Its buttons went with it: the section has the focus instead, where it is
    heading.value?.focus({ preventScroll: true })
  } catch (e) {
    error.value = `The sign-in wasn't unlinked: ${errorMessage(e)}`
  } finally {
    busy.value = false
  }
}

async function claim() {
  if (busy.value || !props.me) return
  busy.value = true
  error.value = undefined
  try {
    const { account } = await graphql<{ account: Account }>(LINK_ACCOUNT, { id: props.me.id, person: props.person.id })
    added.value = [...added.value, account]
    emit('changed')
    heading.value?.focus({ preventScroll: true })
  } catch (e) {
    error.value = `Your sign-in wasn't linked: ${errorMessage(e)}`
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section v-if="shown.length || claimable" :aria-labelledby="`${id}-heading`">
    <div class="heading">
      <h2 :id="`${id}-heading`" ref="heading" tabindex="-1">Sign-ins</h2>
      <span class="count">{{ shown.length }}</span>
    </div>

    <ul v-if="shown.length" class="sign-ins">
      <li v-for="account in shown" :key="account.id" class="sign-in">
        <div class="row">
          <p class="what">
            <span class="name">{{ accountName(account) }}</span>
            <span v-if="account.id === me?.id" class="you">you</span>
            <span class="how">{{ signInDetails(account) }}</span>
          </p>
          <button v-if="canLink && confirming !== account.id" :id="`${id}-unlink-${account.id}`" type="button" class="quiet"
                  :aria-label="`Unlink ${accountName(account)}`" @click="askToUnlink(account)">Unlink</button>
        </div>

        <div v-if="confirming === account.id" class="confirm" role="group" :aria-labelledby="`${id}-question`"
             @keydown.esc="keep(account)">
          <p :id="`${id}-question`" class="question">
            Unlink {{ accountName(account) }} from {{ person.name }}?
            <span>Its comments and history will show its username instead.</span>
          </p>
          <span class="buttons">
            <button type="button" class="button unlink" :aria-disabled="busy || undefined" @click="unlink(account)">
              {{ busy ? 'Unlinking…' : 'Unlink' }}
            </button>
            <button :id="`${id}-keep`" type="button" class="button" :disabled="busy" @click="keep(account)">Cancel</button>
          </span>
        </div>
      </li>
    </ul>

    <div v-if="claimable && me" class="claim">
      <p>
        Nobody signs in as {{ person.name }} yet. Is that you? Your sign-in, {{ accountName(me) }}, isn't linked to
        anyone.
      </p>
      <button type="button" class="button" :aria-disabled="busy || undefined" @click="claim">
        {{ busy ? 'Linking…' : 'This is me' }}
      </button>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

// Focused only when the buttons it had the focus on are gone
h2:focus {
  outline: 0;
}

// One a row, as the page's other lists
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
  font-size: var(--text-micro);
  line-height: 18px;
  font-weight: 600;
  color: var(--accent-text);
}

.how {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

// Quiet, at the row's end, as tall as icon links
.quiet {
  flex: none;
  min-height: var(--icon-link);
  padding: 0 8px;
  margin-right: -8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  cursor: pointer;

  &:hover {
    color: var(--ink);
    background: var(--hover);
  }
}

// The question, and its buttons together at the end, under it where they don't fit beside it
.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px 16px;
  margin-top: 8px;
  padding: 10px 14px;
  background: var(--surface);
  border: 1px solid var(--rule);
  border-radius: var(--radius);

  .question {
    flex: 1 1 260px;
    font-weight: 500;

    span {
      display: block;
      font-size: var(--text-small);
      line-height: 20px;
      font-weight: 400;
      color: var(--secondary);
    }
  }

  .buttons {
    display: flex;
    gap: 8px;
  }
}

.button.unlink {
  color: var(--accent-text);

  &:hover {
    color: var(--accent-text);
    border-color: var(--accent);
  }
}

.button[aria-disabled='true'], .button:disabled {
  color: var(--muted);
  border-color: var(--hairline);
  cursor: default;
}

// You, on the page of someone nobody signs in as yet
.claim {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 16px;
  padding: 14px 16px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);

  p {
    flex: 1 1 320px;
    color: var(--secondary);
  }
}

.sign-ins + .claim {
  margin-top: 12px;
}

.error {
  margin-top: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

</style>
