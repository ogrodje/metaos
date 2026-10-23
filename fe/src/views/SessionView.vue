<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { COMMENT_FIELDS } from '@/comments'
import DiscussionSection from '@/components/DiscussionSection.vue'
import DocLinks from '@/components/DocLinks.vue'
import DropboxFolderList from '@/components/DropboxFolderList.vue'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import HistoryTimeline from '@/components/HistoryTimeline.vue'
import KoofrFolderList from '@/components/KoofrFolderList.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NotFound from '@/components/NotFound.vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import {
  DROPBOX_FIELDS, EPISODE_FIELDS, formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, photosOf, type SessionDetails,
  sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'

const auth = useAuthStore()
const route = useRoute()

// Undefined while it loads; null when there's no such session
const session = ref<SessionDetails | null>()
const error = ref<string>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// Quietly when MetaOS's data changes: what's shown stays until the new is there, and stays if it doesn't come
async function loadSession(id: string, quietly = false) {
  const load = ++loads
  if (!quietly) {
    session.value = undefined
    error.value = undefined
  }
  if (!auth.authenticated) return
  try {
    const data = await graphql<{ session: SessionDetails | null }>(`query ($id: ID!) {
      session(id: $id) {
        id date location address documents { fileId url } ${KOOFR_FIELDS} ${DROPBOX_FIELDS} ${HISTORY_FIELDS}
        ${COMMENT_FIELDS} episodes { ${EPISODE_FIELDS} }
      }
    }`, { id })
    if (load !== loads) return
    session.value = keepUnchanged(session.value, data.session)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// The session in the path: again when the path changes, or the user signs in or out
watch([() => route.params.id, () => auth.authenticated], ([id]) => void loadSession(String(id)), { immediate: true })
useLiveRefresh(() => loadSession(String(route.params.id), true))

usePageTitle(() => (session.value ? sessionTitle(session.value) : undefined))

// After a comment was written here: the live updates would bring it too, a moment later
function reloadQuietly(id: string) {
  loadSession(id, true).catch((error) => console.error('The session did not load again', error))
}

// The same list while it's the same, as the photos page through them from the first again when they change
const photos = computed<KoofrPhoto[]>((previous) =>
  keepUnchanged(previous, session.value ? photosOf(session.value.koofrFolders) : []))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to see this session">
      A recording session's details and history are for Ogrodje's team. Sign in with Discord or Google, or use MetaOS
      from your AI assistant over MCP:
      <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>
    <LoadError v-else-if="error" what="The session" :error="error" @retry="loadSession(String(route.params.id))"/>
    <NotFound v-else-if="session === null" title="There's no such recording session">
      It may have been deleted. The home page lists the upcoming recordings, and each episode's page its session.
    </NotFound>
    <LoadingRows v-else-if="!session" :rows="6" label="Loading the session"/>

    <template v-else>
      <header class="title">
        <p class="kicker">Recording session</p>
        <h1><time :datetime="session.date">{{ formatDate(session.date) }}</time></h1>
        <p v-if="session.location" class="subtitle">{{ session.location }}</p>
      </header>

      <section v-if="session.address || session.documents.length || session.koofrFolders.length || session.dropboxFolders.length"
               aria-labelledby="details">
        <div class="heading"><h2 id="details">Details</h2></div>
        <dl class="details">
          <div v-if="session.address">
            <dt>Address</dt>
            <dd>{{ session.address }}</dd>
          </div>
          <div v-if="session.documents.length">
            <dt>Docs</dt>
            <dd class="icons"><DocLinks :documents="session.documents"/></dd>
          </div>
          <div v-if="session.koofrFolders.length">
            <dt>Koofr</dt>
            <dd><KoofrFolderList :folders="session.koofrFolders"/></dd>
          </div>
          <div v-if="session.dropboxFolders.length">
            <dt>Dropbox</dt>
            <dd><DropboxFolderList :folders="session.dropboxFolders"/></dd>
          </div>
        </dl>
      </section>

      <PhotoStrip v-if="photos.length" title="Photos" :photos="photos" :folder="session.koofrFolders[0]?.url" service="Koofr"/>

      <section aria-labelledby="episodes">
        <div class="heading">
          <h2 id="episodes">Episodes</h2>
          <span class="count">{{ session.episodes.length }}</span>
        </div>
        <p v-if="!session.episodes.length" class="note">No episodes are in this session yet.</p>
        <!-- Each as the other lists have it: a line while it's in the works, its summary once it's released -->
        <ul v-else class="episodes">
          <template v-for="episode in session.episodes" :key="episode.id">
            <EpisodeItem v-if="episode.status === 'PUBLISHED'" :episode="episode"/>
            <EpisodeRow v-else :episode="episode" under-session/>
          </template>
        </ul>
      </section>

      <DiscussionSection :comments="session.comments" :session-id="session.id" @changed="reloadQuietly(session.id)"/>

      <section aria-labelledby="history">
        <div class="heading">
          <h2 id="history">History</h2>
          <span class="count">{{ session.history.length }}</span>
        </div>
        <HistoryTimeline :changes="session.history"/>
      </section>
    </template>
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
