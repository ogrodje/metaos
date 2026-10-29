<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { faLock } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { episodeConcerns } from '@/attention'
import { COMMENT_FIELDS } from '@/comments'
import ActivityPanel from '@/components/ActivityPanel.vue'
import BehindEpisode from '@/components/BehindEpisode.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import FoldedText from '@/components/FoldedText.vue'
import ListenLinks from '@/components/ListenLinks.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import NotFound from '@/components/NotFound.vue'
import PanelTitle from '@/components/PanelTitle.vue'
import PersonLine from '@/components/PersonLine.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import {
  ARTWORK_FIELDS, artworkOf, DROPBOX_FIELDS, type DropboxPicture, EPISODE_FIELDS, type EpisodeDetails, episodePath,
  formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, links, listPicture, photosOf, PICTURE_FIELDS, relativeDate,
  roleName, sessionPath, sessionTitle,
} from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { IMAGE_FIELDS, src, srcset } from '@/images'
import { MEDIA_FIELDS, mediaConcerns } from '@/media'
import { personPath } from '@/people'
import { useScrolledPast } from '@/scroll'
import { episodeData, plainText, shorten, usePageMeta, useSiteUrl } from '@/seo'
import { useFirstLoad } from '@/ssr'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { EPISODE_TRANSCRIPT_FIELDS } from '@/transcriptions'

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
      ${EPISODE_FIELDS} titleB titleC summary machineSummary addedDate language recommendedBy { id name } ${KOOFR_FIELDS}
      ${DROPBOX_FIELDS} ${ARTWORK_FIELDS} ${PICTURE_FIELDS} ${HISTORY_FIELDS} ${COMMENT_FIELDS} ${MEDIA_FIELDS}
      ${EPISODE_TRANSCRIPT_FIELDS} people { avatar { ${IMAGE_FIELDS} } }
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

// After a comment was written or something uploaded here: the live updates would bring it too, a moment later
function reloadQuietly(id: string) {
  loadEpisode(id, true).catch((error) => console.error('The episode did not load again', error))
}

// The team sees the page as everyone else does, but for what's theirs on top; and, released, as the public does, at
// their asking, to see what it shows
const asPublic = ref(false)
const released = computed(() => episode.value?.status === 'PUBLISHED')
const team = computed(() => auth.team && !asPublic.value)

// The pictures in its Dropbox folders, which open in the folder of the first; and its photos, in its first Koofr folder.
// The same lists while they're the same, as the photos page through them from the first again when they change.
const artwork = computed<DropboxPicture[]>((previous) =>
  keepUnchanged(previous, episode.value ? artworkOf(episode.value.dropboxFolders) : []))
const photos = computed<KoofrPhoto[]>((previous) =>
  keepUnchanged(previous, episode.value ? photosOf(episode.value.koofrFolders) : []))

// What needs the team's attention, by the home page's rules, e.g. recorded months ago and not out yet, and in its video
// and audio
const concerns = computed(() => (team.value && episode.value
  ? [...episodeConcerns(episode.value), ...mediaConcerns(episode.value.media)]
  : []))

// Its thumbnail, large; the team also sees those it's A/B tested with, B and C, and switches between them
const variants = computed(() => (episode.value
  ? [
    { letter: 'A', title: episode.value.title, image: episode.value.thumbnail },
    { letter: 'B', title: episode.value.titleB, image: episode.value.thumbnailB },
    { letter: 'C', title: episode.value.titleC, image: episode.value.thumbnailC },
  ].filter((variant) => variant.image)
  : []))
const tested = computed(() => (team.value ? variants.value : variants.value.filter((variant) => variant.letter === 'A')))
const letter = ref('A')
const picture = computed(() => (tested.value.find((variant) => variant.letter === letter.value) ?? tested.value[0]) ?? null)

// What it's spoken in, by name, e.g. Slovenian
const languages = new Intl.DisplayNames('en-GB', { type: 'language' })
const spokenIn = computed(() => (episode.value ? languages.of(episode.value.language.split('-')[0]!) : undefined))

// For anyone not on the team, where on the page to go, when there's more than one place
const contents = computed(() => [
  ...(episode.value?.summary ? [{ id: 'summary', title: 'Summary' }] : []),
  ...(episode.value?.machineSummary ? [{ id: 'ai-summary', title: 'AI summary' }] : []),
])

// Once its title has scrolled away, the panel says what the page is about
const titleBlock = ref<HTMLElement>()
const pastTitle = useScrolledPast(titleBlock)
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

    <!-- What it is and its thumbnail; at the right, for the team, what needs doing and the conversation, then the facts;
         under them, who's on it, where to listen and what it's about, and for the team, what it's made of. On phones
         the panel comes after the thumbnail. -->
    <div v-else class="workspace">
      <div class="lead">
        <header ref="titleBlock" class="title">
          <h1 lang="sl">{{ episode.title }}</h1>
          <p class="meta">
            <span v-if="episode.code" class="code">{{ episode.code }}</span>
            <ShowBadge v-if="episode.show" :name="episode.show" :color="episode.showColor"/>
            <StatusLabel :status="episode.status"/>
          </p>
          <MarkdownText v-if="episode.tinySummary" class="subtitle" :source="episode.tinySummary" lang="sl"/>
        </header>

        <figure v-if="picture" class="hero">
          <a :href="picture.image!.url" target="_blank" rel="noopener"
             :title="`${picture.image!.name}, ${picture.image!.width} × ${picture.image!.height}`">
            <img :src="src(picture.image!, 1280)" :srcset="srcset(picture.image!)" sizes="(max-width: 960px) 100vw, 800px"
                 :width="picture.image!.width" :height="picture.image!.height" :alt="`Thumbnail ${picture.letter}`">
          </a>
          <div v-if="tested.length > 1" class="tested" role="group" aria-label="Thumbnails it's tested with">
            <FontAwesomeIcon class="lock" :icon="faLock"/>
            <span class="label" aria-hidden="true">Tested as</span>
            <button v-for="variant in tested" :key="variant.letter" type="button" :aria-pressed="variant.letter === picture.letter"
                    :title="variant.title ?? undefined" @click="letter = variant.letter">
              {{ variant.letter }}<span class="visually-hidden">: {{ variant.title }}</span>
            </button>
          </div>
        </figure>
      </div>

      <FactPanel label="About the episode" tall>
        <PanelTitle :title="episode.title" :code="episode.code" :picture="listPicture(episode)" :shown="pastTitle"/>

        <p v-if="asPublic" class="seen-as">
          Seen as the public does.
          <button type="button" class="text-button" @click="asPublic = false">Back to the team's view</button>
        </p>

        <!-- The team's, as are their comments and history, which no one else gets; kept a place while the user who
             was on the team here last time is checked -->
        <template v-if="team">
          <ul v-if="concerns.length" class="concerns" aria-label="Needs attention">
            <li v-for="concern in concerns" :key="concern">{{ concern }}</li>
          </ul>
          <ActivityPanel :comments="episode.comments" :changes="episode.history" :episode-id="episode.id"
                         @changed="reloadQuietly(episode.id)"/>
        </template>
        <section v-else-if="auth.likelyMember && !auth.ready" class="loading" aria-label="Activity">
          <LoadingRows :rows="3" label="Loading the activity"/>
        </section>

        <FactGroup title="Episode">
          <dl>
            <Fact v-if="episode.airedDate" label="Aired">
              <time :datetime="episode.airedDate">{{ formatDate(episode.airedDate) }}</time>
              <span v-if="team" class="aside"> · {{ relativeDate(episode.airedDate) }}</span>
            </Fact>
            <Fact v-if="episode.recordingDate" label="Recording">
              <time :datetime="episode.recordingDate">{{ formatDate(episode.recordingDate) }}</time>
              <span v-if="team" class="aside"> · {{ relativeDate(episode.recordingDate) }}</span>
            </Fact>
            <!-- A session's page is the team's -->
            <Fact v-if="episode.session" label="Session">
              <RouterLink v-if="team" :to="sessionPath(episode.session)">{{ sessionTitle(episode.session) }}</RouterLink>
              <template v-else>{{ sessionTitle(episode.session) }}</template>
            </Fact>
            <Fact v-if="episode.recommendedBy" label="Recommended by">
              <RouterLink :to="personPath(episode.recommendedBy)">{{ episode.recommendedBy.name }}</RouterLink>
            </Fact>
            <Fact v-if="spokenIn" label="Spoken in">{{ spokenIn }}</Fact>
            <Fact v-if="team" label="Added">
              <time :datetime="episode.addedDate">{{ formatDate(episode.addedDate) }}</time>
              <FontAwesomeIcon class="lock" :icon="faLock" title="Only the team sees this"/>
            </Fact>
          </dl>
        </FactGroup>

        <FactGroup v-if="!team && contents.length > 1" title="On this page">
          <ol class="contents">
            <li v-for="part in contents" :key="part.id"><a :href="`#${part.id}`">{{ part.title }}</a></li>
          </ol>
        </FactGroup>

        <p v-if="team && released" class="see-as">
          <button type="button" class="text-button" @click="asPublic = true">See it as the public does</button>
        </p>
      </FactPanel>

      <div class="main">
        <div v-if="episode.people.length || links(episode).length" class="credits">
          <section v-if="episode.people.length" aria-labelledby="people">
            <h2 id="people">People</h2>
            <div class="people">
              <PersonLine v-for="person in episode.people" :id="person.personId" :key="person.personId" :name="person.name"
                          :avatar="person.avatar" :role="person.roles.map(roleName).join(', ')"/>
            </div>
          </section>
          <section v-if="links(episode).length" aria-labelledby="listen">
            <h2 id="listen">Listen</h2>
            <div class="listen"><ListenLinks :episode="episode" labelled/></div>
          </section>
        </div>

        <FoldedText v-if="episode.summary" id="summary" title="Summary" :source="episode.summary"/>
        <FoldedText v-if="episode.machineSummary" id="ai-summary" title="AI summary" :source="episode.machineSummary"/>

        <SignInPrompt v-if="auth.ready && !auth.authenticated" compact title="On Ogrodje's team?">
          This is the page the team works on: its transcript, pictures, files and history are theirs.
        </SignInPrompt>

        <BehindEpisode v-if="team" :episode="episode" :artwork="artwork" :photos="photos" @changed="reloadQuietly(episode.id)"/>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';
@use '../assets/pictures';

.title h1 {
  text-wrap: balance;
}

// The thumbnail, opening the original
.hero {
  position: relative;
  margin: 0;

  a {
    display: block;
    @include pictures.picture;
  }

  img {
    display: block;
    width: 100%;
    height: auto;
    aspect-ratio: 16 / 9;
    object-fit: cover;
  }
}

// The team's: which of the thumbnails it's A/B tested with is shown, on it
.tested {
  position: absolute;
  left: 12px;
  bottom: 12px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: #D4D4D4;
  background: rgba(20, 20, 20, 0.78);
  border-radius: var(--radius);

  .lock {
    margin-left: 6px;
  }

  .label {
    padding: 0 6px;
  }

  button {
    min-width: 26px;
    padding: 2px 8px;
    font: inherit;
    color: inherit;
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    cursor: pointer;

    &:hover {
      color: #FFFFFF;
    }

    &[aria-pressed="true"] {
      font-weight: 600;
      color: var(--ink);
      background: #FFFFFF;
    }

    &:focus-visible {
      outline-color: #FFFFFF;
    }
  }
}

.lock {
  width: 11px;
  height: 11px;
  color: var(--muted);
}

dd .lock {
  margin-left: 6px;
  vertical-align: -1px;
}

// What needs attention, first in the panel: in the accent, each after a dot
.concerns {
  display: grid;
  gap: 6px;
  margin-bottom: 20px;
  padding: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 7px minmax(0, 1fr);
    column-gap: 8px;
    color: var(--accent-text);

    &::before {
      content: '';
      width: 7px;
      height: 7px;
      margin-top: 7px;
      border-radius: 50%;
      background: var(--accent);
    }
  }
}

.activity-panel, .loading {
  padding-bottom: 20px;
}

// The team's view of it as the public's, and back
.seen-as {
  margin-bottom: 16px;
  padding: 8px 12px;
  color: var(--secondary);
  background: var(--surface);
  border: 1px solid var(--rule);
  border-radius: var(--radius);

  .text-button {
    display: block;
  }
}

.see-as {
  padding-top: 12px;
  border-top: 1px solid var(--rule);
}

.contents {
  display: grid;
  padding: 0;
  list-style: none;

  a {
    display: block;
    margin: 0 -8px;
    padding: 4px 8px;
    color: var(--secondary);
    border-radius: var(--radius-sm);

    &:hover {
      color: var(--ink);
      background: var(--hover);
    }
  }
}

// Who's on it and where to listen, side by side under the thumbnail
.credits {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px 40px;

  h2 {
    margin-bottom: 10px;
    font-size: var(--text-caption);
    line-height: 16px;
    font-weight: 600;
    color: var(--muted);
  }
}

.people {
  display: grid;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;
}

.listen {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.aside {
  color: var(--muted);
}

dd a {
  @include page.link;
}

// The summaries' headings, under the anchors of the panel's contents, clear of the top
#summary, #ai-summary {
  scroll-margin-top: 24px;
}

@media (max-width: 640px) {
  .credits {
    grid-template-columns: minmax(0, 1fr);
  }

  .tested {
    left: 8px;
    bottom: 8px;
  }
}
</style>
