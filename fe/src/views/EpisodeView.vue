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
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import NotFound from '@/components/NotFound.vue'
import PhotoStrip from '@/components/PhotoStrip.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import {
  ARTWORK_FIELDS, artworkOf, DROPBOX_FIELDS, type DropboxPicture, EPISODE_FIELDS, type EpisodeDetails, episodePath,
  formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, links, photosOf, PICTURE_FIELDS, roleName, sessionPath,
  sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { personPath } from '@/people'
import { episodeData, plainText, shorten, usePageMeta, useSiteUrl } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const siteUrl = useSiteUrl()

// The page is for the episode in the path when it opens: going to another episode opens another page
const episodeRef = String(route.params.ref)

// Undefined while it loads; null when there's no such episode, or none the user may see
const episode = ref<EpisodeDetails | null>()
const error = ref<string>()

/** Whether [episodeRef], from the path, is [episode]'s code or ID */
function names(episode: EpisodeDetails, episodeRef: unknown) {
  return episodeRef === episode.code || episodeRef === episode.id
}

// Everything about it; what's the team's comes back empty for anyone else, e.g. its history (GraphQL.kt)
async function fetchEpisode(ref: string) {
  return (await graphql<{ episode: EpisodeDetails | null }>(`query ($ref: String!) {
    episode(ref: $ref) {
      ${EPISODE_FIELDS} titleB titleC summary machineSummary addedDate recommendedBy { id name } ${KOOFR_FIELDS} ${DROPBOX_FIELDS} ${ARTWORK_FIELDS}
      ${PICTURE_FIELDS} ${HISTORY_FIELDS} ${COMMENT_FIELDS}
    }
  }`, { ref })).episode
}

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// The episode by its code or ID. Quietly when MetaOS's data changes, when what's shown stays if it doesn't come.
async function loadEpisode(ref: string, quietly = false) {
  const load = ++loads
  try {
    const loaded = await fetchEpisode(ref)
    if (load !== loads) return
    episode.value = keepUnchanged(episode.value, loaded)
    error.value = undefined
    // It got another code, which the path follows
    if (quietly && loaded && !names(loaded, route.params.ref)) void router.replace(episodePath(loaded))
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e)
  }
}

// First as anyone may see it, as the server rendered it: a released episode without what's the team's
const first = await useFirstLoad(`episode:${episodeRef}`, () => fetchEpisode(episodeRef))
episode.value = first.data
error.value = first.error

// Again when the user signs in or out: on the team, with what's theirs, and also an episode that isn't released, which
// was none so far, and loads until it's known
watch(() => auth.team, () => {
  if (episode.value === null) episode.value = undefined
  void loadEpisode(episode.value?.id ?? episodeRef)
})

// By its ID, which stays when its code changes
useLiveRefresh(() => loadEpisode(episode.value?.id ?? episodeRef, true))

// In the browser's tab by its code and title, e.g. S05E08 Poletna vročica
usePageTitle(() => (episode.value ? [episode.value.code, episode.value.title].filter(Boolean).join(' ') : undefined))

// For search engines and link previews: what it's about, its thumbnail, and, released, that it's a podcast episode
usePageMeta(() => {
  const shown = episode.value
  if (!shown) return {}
  const about = shown.tinySummary ?? shown.summary
  return {
    path: episodePath(shown),
    description: about ? shorten(plainText(about)) : null,
    image: shown.thumbnail,
    structured: shown.status === 'PUBLISHED' ? episodeData(shown, siteUrl) : null,
  }
})

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
    <LoadError v-if="error" what="The episode" :error="error" @retry="loadEpisode(episodeRef)"/>
    <!-- None so far, while it's checked whether the user is on the team, who may see it although it isn't released -->
    <LoadingRows v-else-if="episode === undefined || (episode === null && auth.likelyMember && !auth.ready)" :rows="6"
                 label="Loading the episode"/>
    <NotFound v-else-if="episode === null" :title="`There's no episode ${episodeRef}`" :search="episodeRef">
      <template v-if="auth.team">
        It may have another code now, or have been deleted. Search for it, or find it among the episodes.
      </template>
      <template v-else>
        It may not be released yet, have another code now, or have been deleted. Find it among the episodes, or sign in if
        you're on Ogrodje's team.
      </template>
    </NotFound>

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
          <!-- A session's page is the team's -->
          <div v-if="episode.session">
            <dt>Session</dt>
            <dd>
              <RouterLink v-if="auth.team" :to="sessionPath(episode.session)">{{ sessionTitle(episode.session) }}</RouterLink>
              <template v-else>{{ sessionTitle(episode.session) }}</template>
            </dd>
          </div>
          <div v-if="episode.recordingDate">
            <dt>Recording</dt>
            <dd><time :datetime="episode.recordingDate">{{ formatDate(episode.recordingDate) }}</time></dd>
          </div>
          <div v-if="episode.airedDate">
            <dt>Aired</dt>
            <dd><time :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time></dd>
          </div>
          <div v-if="auth.team">
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

      <EpisodePictures :episode="episode" @changed="reloadQuietly(episode.id)"/>
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

      <!-- The team's, as are their comments, which no one else gets -->
      <DiscussionSection v-if="auth.team" :comments="episode.comments" :episode-id="episode.id"
                         @changed="reloadQuietly(episode.id)"/>

      <section v-if="auth.team" aria-labelledby="history">
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
