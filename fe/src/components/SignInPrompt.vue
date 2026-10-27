<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { faDiscord, faGoogle } from '@fortawesome/free-brands-svg-icons'
import { faArrowRight } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { useAuthStore } from '@/stores/auth'

// What a visitor who isn't signed in can do: sign in, or use MetaOS over MCP. The slot says why to. Compact, a line
// under what the page shows everyone, e.g. the home page's releases, with only the sign-ins.
defineProps<{
  title: string
  compact?: boolean
}>()

const auth = useAuthStore()
</script>

<template>
  <section class="sign-in" :class="{ compact }" aria-labelledby="sign-in">
    <h2 id="sign-in">{{ title }}</h2>
    <p><slot/></p>
    <div class="actions">
      <button type="button" class="button" @click="auth.login('discord')">
        <FontAwesomeIcon :icon="faDiscord"/>Sign in with Discord
      </button>
      <button type="button" class="button" @click="auth.login('google')">
        <FontAwesomeIcon :icon="faGoogle"/>Sign in with Google
      </button>
      <RouterLink v-if="!compact" class="button" to="/usage">API and MCP usage<FontAwesomeIcon :icon="faArrowRight"/></RouterLink>
    </div>
    <p v-if="!compact" class="approval">New accounts get in once an admin has approved them.</p>
  </section>
</template>

<style scoped lang="scss">

.sign-in {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 24px;
  border: 1px solid var(--hairline);
  border-radius: 12px;

  h2 {
    font-size: 18px;
    line-height: 26px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }

  p {
    max-width: 640px;
    color: var(--secondary);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 8px;
  }

  .approval {
    font-size: 13px;
    line-height: 20px;
    color: var(--muted);
  }
}

// A line under the page: who it's for and why, beside the sign-ins
.sign-in.compact {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 2px 24px;
  padding: 20px 0 0;
  border: 0;
  border-top: 1px solid var(--hairline);
  border-radius: 0;

  h2 {
    font-size: 14px;
    line-height: 20px;
  }

  p {
    grid-column: 1;
    font-size: 13px;
    line-height: 20px;
  }

  .actions {
    grid-column: 2;
    grid-row: 1 / span 2;
    margin-top: 0;
  }

  @media (max-width: 640px) {
    grid-template-columns: minmax(0, 1fr);

    .actions {
      grid-column: 1;
      grid-row: auto;
      margin-top: 10px;
    }
  }
}

</style>
