<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import type { IconDefinition } from '@fortawesome/fontawesome-svg-core'
import { faDiscord, faGithub, faGoogle } from '@fortawesome/free-brands-svg-icons'
import {
  faArrowRightFromBracket, faArrowUpRightFromSquare, faCircleCheck, faCircleExclamation, faCircleQuestion, faKey, faUser, faXmark,
} from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { type LinkOutcome, type SignInProvider } from '@/accounts'
import { errorMessage } from '@/graphql'
import { personPath } from '@/people'
import { useAuthStore } from '@/stores/auth'

// You, at the right of the header. Signed in, your initials, which open your name, your page, the identity providers
// you sign in with, and Sign out; otherwise Sign in, which asks with what. Linking another identity provider is for
// everyone, as it only adds a way to sign in to the same Keycloak user, not which person that is.
const props = defineProps<{
  // Who MetaOS takes the signed-in user for
  name?: string
  // Their person: undefined until MetaOS has said, null when their account isn't linked to one
  person?: { id: string } | null
  // Whether they may link accounts to people, which only one user may
  canLinkAccounts?: boolean
}>()

const auth = useAuthStore()
const route = useRoute()
const id = useId()

const root = ref<HTMLElement>()
const toggle = ref<HTMLButtonElement>()
const open = ref(false)

// The first letters of the first and the last name, past a "dr.": Oto Brglez is OB
const initials = computed(() => {
  const words = (props.name ?? '').replace(/^dr\.\s+/i, '').split(/\s+/).filter(Boolean)
  const first = words[0]?.charAt(0) ?? ''
  const last = words.length > 1 ? (words[words.length - 1]?.charAt(0) ?? '') : ''
  return (first + last).toUpperCase() || '?'
})

// The realm's identity providers, from Keycloak each time the menu opens; what's shown stays while it asks. Undefined
// until it answers; when it can't, e.g. as the token lacks the account's roles, the menu links to the Account Console.
const providers = ref<SignInProvider[]>()
const providersFailed = ref(false)
// The one being linked, while Keycloak's page loads
const linking = ref<string>()

async function loadProviders() {
  try {
    providers.value = await auth.signInProviders()
    providersFailed.value = false
  } catch (error) {
    console.error("Keycloak didn't list the sign-ins", errorMessage(error))
    if (!providers.value) providersFailed.value = true
  }
}

function link(provider: SignInProvider) {
  linking.value = provider.alias
  auth.linkProvider(provider)
}

// Brands MetaOS's realm has, or may: anything else is a key
const PROVIDER_ICONS: Record<string, { icon: IconDefinition, color: string }> = {
  google: { icon: faGoogle, color: '#4285F4' },
  discord: { icon: faDiscord, color: '#5865F2' },
  github: { icon: faGithub, color: '#181717' },
}

function providerIcon(provider: SignInProvider) {
  return PROVIDER_ICONS[provider.alias.toLowerCase()] ?? { icon: faKey, color: 'var(--ink)' }
}

// How linking went, once the page is back from Keycloak: shown under your initials until you've seen it, which for
// a link that worked is a few seconds, or until the menu opens, which shows it as linked. Put into its live region a
// moment after the page shows, so screen readers say it.
const outcome = ref<LinkOutcome>()
let outcomeTimer: ReturnType<typeof setTimeout> | undefined

function dismissOutcome() {
  clearTimeout(outcomeTimer)
  outcome.value = undefined
  auth.dismissLinked()
}

watch(() => auth.linked, (linked) => {
  clearTimeout(outcomeTimer)
  if (!linked) return
  // Keycloak's page didn't load, e.g. as keycloak-js couldn't make its address
  linking.value = undefined
  outcomeTimer = setTimeout(() => {
    outcome.value = linked
    if (linked.linked) outcomeTimer = setTimeout(dismissOutcome, 8000)
  }, 300)
}, { immediate: true })

watch(open, (isOpen) => {
  if (!isOpen) return
  if (outcome.value || auth.linked) dismissOutcome()
  if (auth.authenticated) void loadProviders()
})

function close() {
  // Back on the button, as what had the focus goes with the menu
  const focused = root.value?.contains(document.activeElement) && document.activeElement !== toggle.value
  open.value = false
  if (focused) void nextTick(() => toggle.value?.focus())
}

// Closed by a click anywhere else, Esc, or going somewhere
function onDocumentClick(event: MouseEvent) {
  if (!root.value?.contains(event.target as Node)) {
    open.value = false
    if (outcome.value) dismissOutcome()
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (open.value) close()
  else if (outcome.value) dismissOutcome()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
  clearTimeout(outcomeTimer)
})
watch(() => route.fullPath, () => { open.value = false })
</script>

<template>
  <div ref="root" class="you">
    <template v-if="auth.authenticated">
      <button ref="toggle" type="button" class="avatar" :aria-expanded="open" :aria-controls="`${id}-menu`" :title="name"
              :aria-label="`${name ?? 'You'}: your page, your sign-ins and signing out`" @click="open = !open">{{ initials }}</button>
      <div v-if="open" :id="`${id}-menu`" class="menu">
        <p class="who">
          <b>{{ name }}</b>
          <span v-if="auth.username && auth.username !== name">{{ auth.username }}</span>
        </p>
        <RouterLink v-if="person" class="item" :to="personPath(person)"><FontAwesomeIcon :icon="faUser"/>Your page</RouterLink>
        <!-- Once MetaOS has said that your account isn't anyone. Only Oto links accounts to people, on the People page,
             which has everyone and those nobody's linked to. -->
        <RouterLink v-else-if="person === null && canLinkAccounts" class="item" to="/people"><FontAwesomeIcon :icon="faCircleQuestion"/>Which person are you?</RouterLink>
        <p v-else-if="person === null" class="unlinked">
          <FontAwesomeIcon :icon="faCircleQuestion"/>
          <span>You aren't linked to a person in MetaOS yet. Oto links sign-ins to people: ask him to link yours.</span>
        </p>

        <!-- The ways you sign in, all to the same Keycloak user, so MetaOS takes them for the same account -->
        <div v-if="!providers || providers.length" class="sign-ins">
          <p :id="`${id}-sign-ins`" class="label">Sign in with</p>
          <a v-if="providersFailed" class="item" :href="auth.accountConsole" target="_blank" rel="noopener">
            <FontAwesomeIcon :icon="faKey"/>Manage sign-ins<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
            <span class="visually-hidden">(opens Keycloak in a new tab)</span>
          </a>
          <p v-else-if="!providers" class="loading">Loading…</p>
          <ul v-else class="providers" :aria-labelledby="`${id}-sign-ins`">
            <li v-for="provider in providers" :key="provider.alias" class="provider">
              <FontAwesomeIcon class="brand" :icon="providerIcon(provider).icon"
                               :style="provider.connected ? { color: providerIcon(provider).color } : undefined"/>
              <span class="what">
                {{ provider.name }}<template v-if="provider.username"><span class="username"> · {{ provider.username }}</span></template>
                <span v-if="provider.connected && !provider.username" class="visually-hidden"> (linked)</span>
              </span>
              <span v-if="provider.connected && provider.alias === auth.identityProvider" class="this">this one</span>
              <button v-else-if="!provider.connected" type="button" class="link" :aria-label="`Link ${provider.name}`"
                      :aria-disabled="linking !== undefined || undefined" @click="linking === undefined && link(provider)">
                {{ linking === provider.alias ? 'Linking…' : 'Link' }}
              </button>
            </li>
          </ul>
        </div>

        <div class="rule" aria-hidden="true"></div>
        <button type="button" class="item" @click="auth.logout()"><FontAwesomeIcon :icon="faArrowRightFromBracket"/>Sign out</button>
      </div>
    </template>

    <template v-else>
      <button type="button" class="button" aria-haspopup="menu" :aria-expanded="open" @click="open = !open">Sign in</button>
      <div v-if="open" class="menu" role="menu">
        <button type="button" class="item" role="menuitem" @click="auth.login('discord')"><FontAwesomeIcon :icon="faDiscord"/>Sign in with Discord</button>
        <button type="button" class="item" role="menuitem" @click="auth.login('google')"><FontAwesomeIcon :icon="faGoogle"/>Sign in with Google</button>
        <p class="note">New accounts get in once an admin has approved them.</p>
      </div>
    </template>

    <!-- How linking a sign-in went, quietly, under your initials -->
    <div class="outcome-region" role="status">
      <p v-if="outcome && !open" class="outcome" :class="{ failed: !outcome.linked }">
        <FontAwesomeIcon class="mark" :icon="outcome.linked ? faCircleCheck : faCircleExclamation"/>
        <span class="text">{{ outcome.text }}</span>
        <button type="button" class="dismiss" aria-label="Dismiss" @click.stop="dismissOutcome"><FontAwesomeIcon :icon="faXmark"/></button>
      </p>
    </div>
  </div>
</template>

<style scoped lang="scss">

.you {
  position: relative;
  display: flex;
}

// Your initials in a dot of ink, ringed in the logo's pink while the menu is open
.avatar {
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--ink);
  color: #FFFFFF;
  font-size: 11.5px;
  line-height: 32px;
  font-weight: 600;
  letter-spacing: .02em;
  cursor: pointer;

  &[aria-expanded='true'] {
    box-shadow: 0 0 0 3px #FFFFFF, 0 0 0 4.5px var(--accent);
  }
}

// Under the button, at its right edge; never wider than a phone leaves it
.menu, .outcome {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  max-width: calc(100vw - 32px);
  background: #FFFFFF;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, .08), 0 18px 40px -12px rgba(0, 0, 0, .28);
}

.menu {
  width: 296px;
  padding: 6px;
}

// Not signed in, it only asks with what
.menu[role='menu'] {
  width: auto;
  min-width: 232px;
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 6px;
  background: none;
  font-size: 14px;
  line-height: 20px;
  color: var(--ink);
  text-align: left;
  white-space: nowrap;
  cursor: pointer;

  &:hover {
    background: var(--hover);
    color: var(--ink);
  }

  svg {
    width: 16px;
    color: var(--muted);
  }

  .external {
    width: 12px;
    margin-left: auto;
  }
}

.who {
  display: flex;
  flex-direction: column;
  padding: 8px 10px 10px;
  margin-bottom: 6px;
  border-bottom: 1px solid var(--rule);
  font-size: 14px;
  line-height: 20px;

  b {
    font-weight: 600;
  }

  span {
    font-size: 12px;
    line-height: 18px;
    color: var(--muted);
    overflow-wrap: anywhere;
  }
}

// Not anyone yet: what to do about it, in a menu item's place
.unlinked {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 20px;
  color: var(--secondary);

  svg {
    flex: none;
    width: 16px;
    height: 20px;
    color: var(--muted);
  }
}

.sign-ins {
  margin-top: 6px;
}

.label {
  padding: 6px 10px 2px;
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
  letter-spacing: .06em;
  text-transform: uppercase;
  color: var(--muted);
}

.loading {
  padding: 8px 10px;
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

.providers {
  padding: 0;
  list-style: none;
}

// An identity provider: its logo and name, who you are there, and Link when you aren't
.provider {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 4px 6px 4px 10px;
  font-size: 13.5px;
  line-height: 20px;

  .brand {
    flex: none;
    width: 16px;
    color: var(--muted);
  }

  .what {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .username {
    color: var(--muted);
  }
}

// The one this sign-in came through, when Keycloak says
.this {
  flex: none;
  font-size: 11.5px;
  line-height: 18px;
  font-weight: 600;
  color: var(--accent-text);
}

// A small outlined button, as the design system's, in a row's height
.link {
  flex: none;
  min-height: 28px;
  padding: 0 10px;
  font-size: 12.5px;
  line-height: 18px;
  font-weight: 500;
  color: var(--ink);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 6px;
  cursor: pointer;

  &:hover {
    border-color: #A3A3A3;
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    border-color: var(--hairline);
    cursor: default;
  }
}

.rule {
  margin: 6px 0;
  border-top: 1px solid var(--rule);
}

.note {
  padding: 6px 10px 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
}

// How linking went: a line under your initials, with its own way to go
.outcome {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 320px;
  padding: 10px 6px 10px 12px;
  font-size: 13px;
  line-height: 20px;

  .mark {
    flex: none;
    width: 14px;
    height: 20px;
    color: var(--secondary);
  }

  &.failed .mark {
    color: var(--accent-text);
  }

  .text {
    flex: 1;
    min-width: 0;
  }
}

.dismiss {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin: -4px 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--muted);
  cursor: pointer;

  &:hover {
    background: var(--hover);
    color: var(--ink);
  }
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

button:focus-visible, a:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

// The menu's own rows, in its padding
.menu .item:focus-visible {
  outline-offset: -2px;
}

</style>
