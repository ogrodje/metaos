<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import IdeaItem from '@/components/IdeaItem.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import { EPISODE_FIELDS, type Idea } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// The episode ideas, the most recently added first; undefined while they load
const ideas = ref<Idea[]>()
const error = ref<string>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Ideas are the team's, so they load only for a signed-in user. Quietly when MetaOS's data changes: what's shown stays
// until the new ones are there, and stays if they don't come.
async function loadIdeas(quietly = false) {
  const load = ++loads
  if (!quietly) {
    ideas.value = undefined
    error.value = undefined
  }
  if (!auth.authenticated) return
  try {
    const data = await graphql<{ ideas: Idea[] }>(`{ ideas { ${EPISODE_FIELDS} addedDate recommendedBy { id name } } }`)
    if (load !== loads) return
    ideas.value = keepUnchanged(ideas.value, data.ideas)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// Again when one signs in
watch(() => auth.authenticated, () => void loadIdeas(), { immediate: true })
useLiveRefresh(() => loadIdeas(true))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to see the ideas">
      Episode ideas are for Ogrodje's team. Sign in with Discord or Google, or use MetaOS from your AI assistant over
      MCP: <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>

    <!-- Once planned, an idea has a code and is listed with the episodes instead -->
    <section v-else aria-labelledby="ideas">
      <div class="heading">
        <h1 id="ideas">Ideas</h1>
        <span v-if="ideas" class="count">{{ ideas.length }}</span>
      </div>
      <LoadError v-if="error" what="The ideas" :error="error" @retry="loadIdeas()"/>
      <LoadingRows v-else-if="!ideas" :rows="6" tall label="Loading the ideas"/>
      <p v-else-if="!ideas.length" class="note">No ideas yet.</p>
      <ul v-else class="episodes">
        <IdeaItem v-for="idea in ideas" :key="idea.id" :idea="idea"/>
      </ul>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
