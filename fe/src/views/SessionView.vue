<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { COMMENT_FIELDS } from '@/comments'
import ActivityFeed from '@/components/ActivityFeed.vue'
import DocLinks from '@/components/DocLinks.vue'
import DropboxFolderList from '@/components/DropboxFolderList.vue'
import EpisodeItem from '@/components/EpisodeItem.vue'
import EpisodeRow from '@/components/EpisodeRow.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import KoofrFolderList from '@/components/KoofrFolderList.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NotFound from '@/components/NotFound.vue'
import PanelTitle from '@/components/PanelTitle.vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import {
  DROPBOX_FIELDS, EPISODE_FIELDS, formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, photosOf, relativeDate,
  type SessionDetails, sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { useScrolledPast } from '@/scroll'
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

// Once its title has scrolled away, the panel says what the page is about
const titleBlock = ref<HTMLElement>()
const pastTitle = useScrolledPast(titleBlock)

const hasFiles = computed(() => !!session.value
  && (session.value.documents.length > 0 || session.value.koofrFolders.length > 0 || session.value.dropboxFolders.length > 0))
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
      <header ref="titleBlock" class="title">
        <p class="kicker">Recording session</p>
        <h1><time :datetime="session.date">{{ formatDate(session.date) }}</time></h1>
        <p v-if="session.location" class="subtitle">{{ session.location }}</p>
      </header>

      <!-- Where and when at the right, its episodes and activity in the main column; on phones the facts come first -->
      <div class="workspace">
        <FactPanel label="About the session">
          <PanelTitle :title="sessionTitle(session)" :shown="pastTitle"/>
          <FactGroup>
            <dl>
              <Fact label="When">
                <time :datetime="session.date">{{ formatDate(session.date) }}</time>
                <span class="aside"> · {{ relativeDate(session.date) }}</span>
              </Fact>
              <Fact v-if="session.address" label="Address">{{ session.address }}</Fact>
            </dl>
          </FactGroup>
          <FactGroup v-if="hasFiles" title="Files">
            <dl>
              <Fact v-if="session.documents.length" label="Docs"><span class="icons"><DocLinks :documents="session.documents"/></span></Fact>
              <Fact v-if="session.koofrFolders.length" label="Koofr" stacked><KoofrFolderList :folders="session.koofrFolders"/></Fact>
              <Fact v-if="session.dropboxFolders.length" label="Dropbox" stacked><DropboxFolderList :folders="session.dropboxFolders"/></Fact>
            </dl>
          </FactGroup>
        </FactPanel>

        <div class="main">
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

          <PhotoStrip v-if="photos.length" title="Photos" :photos="photos" :folder="session.koofrFolders[0]?.url" service="Koofr"/>

          <ActivityFeed :comments="session.comments" :changes="session.history" :session-id="session.id"
                        @changed="reloadQuietly(session.id)"/>
        </div>
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.aside {
  color: var(--muted);
}

// The doc icons' glyphs, not their hit areas, line up with the text
.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
