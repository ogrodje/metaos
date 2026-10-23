<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { faDiscord, faGoogle } from '@fortawesome/free-brands-svg-icons'
import { faArrowRight } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { useAuthStore } from '@/stores/auth'

// What a visitor who isn't signed in can do: sign in, or use MetaOS over MCP. The slot says why to.
defineProps<{ title: string }>()

const auth = useAuthStore()
</script>

<template>
  <section class="sign-in" aria-labelledby="sign-in">
    <h2 id="sign-in">{{ title }}</h2>
    <p><slot/></p>
    <div class="actions">
      <button type="button" class="button" @click="auth.login('discord')">
        <FontAwesomeIcon :icon="faDiscord"/>Sign in with Discord
      </button>
      <button type="button" class="button" @click="auth.login('google')">
        <FontAwesomeIcon :icon="faGoogle"/>Sign in with Google
      </button>
      <RouterLink class="button" to="/usage">API and MCP usage<FontAwesomeIcon :icon="faArrowRight"/></RouterLink>
    </div>
    <p class="approval">New accounts get in once an admin has approved them.</p>
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

</style>
