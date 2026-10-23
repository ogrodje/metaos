<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { faDiscord, faGoogle } from '@fortawesome/free-brands-svg-icons'
import { faArrowRightFromBracket, faUser } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { personPath } from '@/people'
import { useAuthStore } from '@/stores/auth'

// You, at the right of the header. Signed in, your initials, which open your name, your page and Sign out; otherwise
// Sign in, which asks with what. A button that shows and hides a few links and buttons, which Tab goes through, rather
// than a menu, whose role would promise arrow keys.
const props = defineProps<{
  // Who MetaOS takes the signed-in user for, and their person, once one is linked to them by email
  name?: string
  person?: { id: string } | null
}>()

const auth = useAuthStore()
const route = useRoute()

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

// Closed by a click anywhere else, Esc, or going somewhere. Esc puts focus back on the button when it was in the menu,
// so the keyboard carries on from where it was.
function onDocumentClick(event: MouseEvent) {
  if (open.value && !root.value?.contains(event.target as Node)) open.value = false
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !open.value) return
  const inside = root.value?.contains(document.activeElement)
  open.value = false
  if (inside) toggle.value?.focus()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
watch(() => route.fullPath, () => { open.value = false })
</script>

<template>
  <div ref="root" class="you">
    <template v-if="auth.authenticated">
      <button ref="toggle" type="button" class="avatar" aria-controls="you-menu" :aria-expanded="open" :title="name"
              :aria-label="`${name ?? 'You'}: your page and signing out`" @click="open = !open">{{ initials }}</button>
      <div v-if="open" id="you-menu" class="menu">
        <p class="who">
          <b>{{ name }}</b>
          <span v-if="auth.username && auth.username !== name">{{ auth.username }}</span>
        </p>
        <!-- Once a person is linked to the user by their email -->
        <RouterLink v-if="person" :to="personPath(person)"><FontAwesomeIcon :icon="faUser"/>Your page</RouterLink>
        <button type="button" @click="auth.logout()"><FontAwesomeIcon :icon="faArrowRightFromBracket"/>Sign out</button>
      </div>
    </template>

    <template v-else>
      <button ref="toggle" type="button" class="button" aria-controls="you-menu" :aria-expanded="open" @click="open = !open">Sign in</button>
      <div v-if="open" id="you-menu" class="menu">
        <button type="button" @click="auth.login('discord')"><FontAwesomeIcon :icon="faDiscord"/>Sign in with Discord</button>
        <button type="button" @click="auth.login('google')"><FontAwesomeIcon :icon="faGoogle"/>Sign in with Google</button>
        <p class="note">New accounts get in once an admin has approved them.</p>
      </div>
    </template>
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

// Under the button, at its right edge
.menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  min-width: 232px;
  padding: 6px;
  background: #FFFFFF;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, .08), 0 18px 40px -12px rgba(0, 0, 0, .28);

  a, button {
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
  }
}

.note {
  padding: 6px 10px 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
}

</style>
