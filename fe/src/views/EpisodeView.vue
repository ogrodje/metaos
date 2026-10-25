<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { episodeConcerns } from '@/attention'
import { COMMENT_FIELDS } from '@/comments'
import ActivityFeed from '@/components/ActivityFeed.vue'
import DocLinks from '@/components/DocLinks.vue'
import DropboxFolderList from '@/components/DropboxFolderList.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import FoldedText from '@/components/FoldedText.vue'
import KoofrFolderList from '@/components/KoofrFolderList.vue'
import ListenLinks from '@/components/ListenLinks.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import MediaStrip from '@/components/MediaStrip.vue'
import NotFound from '@/components/NotFound.vue'
import PanelTitle from '@/components/PanelTitle.vue'
import PersonLine from '@/components/PersonLine.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import {
  ARTWORK_FIELDS, artworkOf, DROPBOX_FIELDS, type DropboxPicture, EPISODE_FIELDS, type EpisodeDetails, episodePath,
  formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, links, listPicture, photosOf, PICTURE_FIELDS, relativeDate,
  roleName, sessionPath, sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { IMAGE_FIELDS } from '@/images'
import { MEDIA_FIELDS, mediaConcerns } from '@/media'
import { personPath } from '@/people'
import { useScrolledPast } from '@/scroll'
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
      ${PICTURE_FIELDS} ${HISTORY_FIELDS} ${COMMENT_FIELDS} ${MEDIA_FIELDS} people { avatar { ${IMAGE_FIELDS} } }
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

// What needs the team's attention, by the home page's rules, e.g. recorded months ago and not out yet, and in its video
// and audio
const concerns = computed(() => (auth.team && episode.value
  ? [...episodeConcerns(episode.value), ...mediaConcerns(episode.value.media)]
  : []))

// Once its title has scrolled away, the panel says what the page is about
const titleBlock = ref<HTMLElement>()
const pastTitle = useScrolledPast(titleBlock)

const hasFiles = computed(() => !!episode.value
  && (episode.value.documents.length > 0 || episode.value.koofrFolders.length > 0 || episode.value.dropboxFolders.length > 0))
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
      <header ref="titleBlock" class="title">
        <p class="kicker">
          <span v-if="episode.code" class="code">{{ episode.code }}</span>
          <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
          <StatusLabel :status="episode.status"/>
        </p>
        <h1 lang="sl">{{ episode.title }}</h1>
        <MarkdownText v-if="episode.tinySummary" class="subtitle" :source="episode.tinySummary" lang="sl"/>
      </header>

      <!-- The facts at the right, what the team works on in the main column; on phones the facts come first -->
      <div class="workspace">
        <FactPanel label="About the episode">
          <PanelTitle :title="episode.title" :code="episode.code" :picture="listPicture(episode)" :shown="pastTitle"/>
          <FactGroup>
            <dl>
              <Fact v-if="episode.airedDate" label="Aired">
                <time :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time>
                <span v-if="auth.team" class="aside"> · {{ relativeDate(episode.airedDate) }}</span>
              </Fact>
              <Fact v-if="episode.recordingDate" label="Recording">
                <time :datetime="episode.recordingDate">{{ formatDate(episode.recordingDate) }}</time>
                <span v-if="auth.team" class="aside"> · {{ relativeDate(episode.recordingDate) }}</span>
              </Fact>
              <!-- A session's page is the team's -->
              <Fact v-if="episode.session" label="Session">
                <RouterLink v-if="auth.team" :to="sessionPath(episode.session)">{{ sessionTitle(episode.session) }}</RouterLink>
                <template v-else>{{ sessionTitle(episode.session) }}</template>
              </Fact>
              <Fact v-if="episode.recommendedBy" label="Recommended by">
                <RouterLink :to="personPath(episode.recommendedBy)">{{ episode.recommendedBy.name }}</RouterLink>
              </Fact>
              <Fact v-if="auth.team" label="Added">
                <time :datetime="episode.addedDate">{{ formatDate(episode.addedDate) }}</time>
              </Fact>
            </dl>
            <p v-for="concern in concerns" :key="concern" class="concern">{{ concern }}</p>
          </FactGroup>

          <FactGroup v-if="episode.people.length" title="People" :count="episode.people.length">
            <PersonLine v-for="person in episode.people" :id="person.personId" :key="person.personId" :name="person.name"
                        :avatar="person.avatar" :role="person.roles.map(roleName).join(', ')"/>
          </FactGroup>

          <FactGroup v-if="links(episode).length" title="Listen">
            <div class="listen"><ListenLinks :episode="episode" labelled/></div>
          </FactGroup>

          <!-- The team's, as everyone else gets none -->
          <FactGroup v-if="hasFiles" title="Files">
            <dl>
              <Fact v-if="episode.documents.length" label="Docs"><span class="icons"><DocLinks :documents="episode.documents"/></span></Fact>
              <Fact v-if="episode.koofrFolders.length" label="Koofr" stacked><KoofrFolderList :folders="episode.koofrFolders"/></Fact>
              <Fact v-if="episode.dropboxFolders.length" label="Dropbox" stacked><DropboxFolderList :folders="episode.dropboxFolders"/></Fact>
            </dl>
          </FactGroup>
        </FactPanel>

        <div class="main">
          <MediaStrip :episode="episode" :artwork="artwork" :photos="photos" :team="auth.team" @changed="reloadQuietly(episode.id)"/>
          <FoldedText v-if="episode.summary" title="Summary" :source="episode.summary"/>
          <FoldedText v-if="episode.machineSummary" title="AI summary" :source="episode.machineSummary"/>

          <!-- The team's, as are their comments and history, which no one else gets; kept a place while the user who
               was on the team here last time is checked -->
          <ActivityFeed v-if="auth.team" :comments="episode.comments" :changes="episode.history" :episode-id="episode.id"
                        @changed="reloadQuietly(episode.id)"/>
          <section v-else-if="auth.likelyMember" aria-label="Activity">
            <LoadingRows :rows="4" label="Loading the activity"/>
          </section>
        </div>
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// What needs attention, under the facts it's about
.concern {
  padding: 8px 12px;
  font-size: 13px;
  line-height: 20px;
  color: var(--accent-text);
  background: #FDF0F3;
  border-radius: 8px;
}

.listen {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

// The doc icons' glyphs, not their hit areas, line up with the text
.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

.aside {
  color: var(--muted);
}

dd a {
  @include page.link;
}
</style>
