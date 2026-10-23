<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { COMMENT_FIELDS } from '@/comments'
import DiscussionSection from '@/components/DiscussionSection.vue'
import DocLinks from '@/components/DocLinks.vue'
import DropboxFolderList from '@/components/DropboxFolderList.vue'
import EpisodePictures from '@/components/EpisodePictures.vue'
import HistoryTimeline from '@/components/HistoryTimeline.vue'
import KoofrFolderList from '@/components/KoofrFolderList.vue'
import ListenLinks from '@/components/ListenLinks.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import {
  ARTWORK_FIELDS, artworkOf, DROPBOX_FIELDS, type DropboxPicture, EPISODE_FIELDS, type EpisodeDetails, episodePath,
  formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, links, photosOf, PICTURE_FIELDS, roleName, sessionPath,
  sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { personPath } from '@/people'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// Undefined while it loads; null when there's no such episode
const episode = ref<EpisodeDetails | null>()
const error = ref<string>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

/** Whether [episodeRef], from the path, is [episode]'s code or ID */
function names(episode: EpisodeDetails, episodeRef: unknown) {
  return episodeRef === episode.code || episodeRef === episode.id
}

// The episode by its code or ID. Quietly when MetaOS's data changes: what's shown stays until the new is there, and
// stays if it doesn't come.
async function loadEpisode(episodeRef: string, quietly = false) {
  const load = ++loads
  if (!quietly) {
    episode.value = undefined
    error.value = undefined
  }
  if (!auth.authenticated) return
  try {
    const data = await graphql<{ episode: EpisodeDetails | null }>(`query ($ref: String!) {
      episode(ref: $ref) {
        ${EPISODE_FIELDS} titleB titleC summary machineSummary addedDate recommendedBy { id name } ${KOOFR_FIELDS} ${DROPBOX_FIELDS} ${ARTWORK_FIELDS}
        ${PICTURE_FIELDS} ${HISTORY_FIELDS} ${COMMENT_FIELDS}
      }
    }`, { ref: episodeRef })
    if (load !== loads) return
    episode.value = keepUnchanged(episode.value, data.episode)
    error.value = undefined
    // It got another code, which the path follows
    if (quietly && data.episode && !names(data.episode, route.params.ref)) void router.replace(episodePath(data.episode))
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// The episode in the path: again when the path changes, unless to the episode shown, or the user signs in or out
watch([() => route.params.ref, () => auth.authenticated], ([episodeRef, authenticated]) => {
  if (authenticated && episode.value && names(episode.value, episodeRef)) return
  void loadEpisode(String(episodeRef))
}, { immediate: true })

// By its ID, which stays when its code changes
useLiveRefresh(() => loadEpisode(episode.value?.id ?? String(route.params.ref), true))

// After a comment was written here: the live updates would bring it too, a moment later
function reloadQuietly(id: string) {
  loadEpisode(id, true).catch((error) => console.error('The episode did not load again', error))
}

// The pictures in its Dropbox folders, which open in the folder of the first; and its photos, in its first Koofr folder.
// The same lists while they're the same, as the photos page through them from the first again when they change.
const artwork = computed<DropboxPicture[]>((previous) =>
  keepUnchanged(previous, episode.value ? artworkOf(episode.value.dropboxFolders) : []))
const photos = computed<KoofrPhoto[]>((previous) =>
  keepUnchanged(previous, episode.value ? photosOf(episode.value.koofrFolders) : []))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" title="Sign in to see this episode">
      An episode's details and history are for Ogrodje's team. Sign in with Discord or Google, or use MetaOS from your
      AI assistant over MCP:
      <RouterLink class="usage" to="/usage">API and MCP usage</RouterLink> shows how to connect it.
    </SignInPrompt>
    <p v-else-if="error" class="note">The episode didn't load: {{ error }}</p>
    <p v-else-if="episode === null" class="note">There's no episode {{ route.params.ref }}.</p>
    <p v-else-if="!episode" class="note">Loading…</p>

    <template v-else>
      <header class="title">
        <p class="kicker">
          <span v-if="episode.code" class="code">{{ episode.code }}</span>
          <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
          <StatusLabel :status="episode.status"/>
        </p>
        <h1 lang="sl">{{ episode.title }}</h1>
        <MarkdownText v-if="episode.tinySummary" :source="episode.tinySummary" lang="sl"/>
      </header>

      <section aria-labelledby="details">
        <div class="heading"><h2 id="details">Details</h2></div>
        <dl class="details">
          <div v-if="episode.titleB || episode.titleC">
            <dt>Alternative titles</dt>
            <dd>
              <ul class="alternatives" lang="sl">
                <li v-if="episode.titleB"><span class="variant">B</span>{{ episode.titleB }}</li>
                <li v-if="episode.titleC"><span class="variant">C</span>{{ episode.titleC }}</li>
              </ul>
            </dd>
          </div>
          <div v-if="episode.session">
            <dt>Session</dt>
            <dd><RouterLink :to="sessionPath(episode.session)">{{ sessionTitle(episode.session) }}</RouterLink></dd>
          </div>
          <div v-if="episode.recordingDate">
            <dt>Recording</dt>
            <dd><time :datetime="episode.recordingDate">{{ formatDate(episode.recordingDate) }}</time></dd>
          </div>
          <div v-if="episode.airedDate">
            <dt>Aired</dt>
            <dd><time :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time></dd>
          </div>
          <div>
            <dt>Added</dt>
            <dd><time :datetime="episode.addedDate">{{ formatDate(episode.addedDate) }}</time></dd>
          </div>
          <div v-if="episode.recommendedBy">
            <dt>Recommended by</dt>
            <dd><RouterLink :to="personPath(episode.recommendedBy)">{{ episode.recommendedBy.name }}</RouterLink></dd>
          </div>
          <div v-if="links(episode).length">
            <dt>Listen</dt>
            <dd class="icons"><ListenLinks :episode="episode"/></dd>
          </div>
          <div v-if="episode.documents.length">
            <dt>Docs</dt>
            <dd class="icons"><DocLinks :documents="episode.documents"/></dd>
          </div>
          <div v-if="episode.koofrFolders.length">
            <dt>Koofr</dt>
            <dd><KoofrFolderList :folders="episode.koofrFolders"/></dd>
          </div>
          <div v-if="episode.dropboxFolders.length">
            <dt>Dropbox</dt>
            <dd><DropboxFolderList :folders="episode.dropboxFolders"/></dd>
          </div>
        </dl>
      </section>

      <EpisodePictures :episode="episode"/>
      <PhotoStrip v-if="artwork.length" title="Artwork" :photos="artwork" :folder="artwork[0]?.url" service="Dropbox" artwork/>
      <PhotoStrip v-if="photos.length" title="Photos" :photos="photos" :folder="episode.koofrFolders[0]?.url" service="Koofr"/>

      <section v-if="episode.people.length" aria-labelledby="people">
        <div class="heading">
          <h2 id="people">People</h2>
          <span class="count">{{ episode.people.length }}</span>
        </div>
        <ul class="rows">
          <li v-for="person in episode.people" :key="person.personId">
            <RouterLink :to="personPath({ id: person.personId })">{{ person.name }}</RouterLink>
            <span class="aside">{{ person.roles.map(roleName).join(', ') }}</span>
          </li>
        </ul>
      </section>

      <section v-if="episode.summary" aria-labelledby="summary">
        <div class="heading"><h2 id="summary">Summary</h2></div>
        <MarkdownText :source="episode.summary" lang="sl"/>
      </section>

      <section v-if="episode.machineSummary" aria-labelledby="ai-summary">
        <div class="heading"><h2 id="ai-summary">AI summary</h2></div>
        <MarkdownText :source="episode.machineSummary" lang="sl"/>
      </section>

      <DiscussionSection :comments="episode.comments" :episode-id="episode.id" @changed="reloadQuietly(episode.id)"/>

      <section aria-labelledby="history">
        <div class="heading">
          <h2 id="history">History</h2>
          <span class="count">{{ episode.history.length }}</span>
        </div>
        <HistoryTimeline :changes="episode.history"/>
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

// Each after its letter in the A/B test; a long one wraps beside it
.alternatives {
  padding: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 20px minmax(0, 1fr);
    align-items: baseline;
  }

  li + li {
    margin-top: 4px;
  }
}

.variant {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--muted);
}
</style>
