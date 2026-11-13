<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { I18nT } from 'vue-i18n'
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
import RenameSession from '@/components/RenameSession.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import {
  DROPBOX_FIELDS, EPISODE_FIELDS, formatDate, HISTORY_FIELDS, KOOFR_FIELDS, type KoofrPhoto, photosOf, relativeDate,
  type Session, type SessionDetails, sessionName, sessionPlace, sessionTitle,
} from '@/episodes'
import { graphql } from '@/graphql'
import { useScrolledPast } from '@/scroll'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { useLocale } from '@/useLocale'

const auth = useAuthStore()
const route = useRoute()
const { t, dateLocale, path } = useLocale()

// Undefined while it loads; null when there's no such session
const session = ref<SessionDetails | null>()
// What went wrong, said in the page's language as it's shown (LoadError)
const error = ref<unknown>()

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
        id date name location address documents { fileId url } ${KOOFR_FIELDS} ${DROPBOX_FIELDS} ${HISTORY_FIELDS}
        ${COMMENT_FIELDS} episodes { ${EPISODE_FIELDS} }
      }
    }`, { id })
    if (load !== loads) return
    session.value = keepUnchanged(session.value, data.session)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = e
  }
}

// The session in the path: again when the path changes, or the user signs in or out
watch([() => route.params.id, () => auth.authenticated], ([id]) => void loadSession(String(id)), { immediate: true })
useLiveRefresh(() => loadSession(String(route.params.id), true))

usePageTitle(() => (session.value ? sessionTitle(session.value, dateLocale.value) : undefined))

// After a comment was written here: the live updates would bring it too, a moment later
function reloadQuietly(id: string) {
  loadSession(id, true).catch((error) => console.error('The session did not load again', error))
}

// Renaming it in place of its heading; back to the Rename button once that's done or called off
const renaming = ref(false)
const renameButton = ref<HTMLButtonElement>()

async function stopRenaming() {
  renaming.value = false
  await nextTick()
  renameButton.value?.focus()
}

// Shown right away, and its history with the live updates a moment later
function renamed(changed: Pick<Session, 'id' | 'name' | 'location'>) {
  if (session.value?.id === changed.id) session.value = { ...session.value, name: changed.name, location: changed.location }
}

// Another session, e.g. one linked from this one's activity, isn't renamed
watch(() => route.params.id, () => { renaming.value = false })

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
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('sessions.signInTitle')">
      <I18nT keypath="sessions.signIn" scope="global">
        <template #usage><RouterLink class="usage" :to="path('/usage')">{{ t('app.usage') }}</RouterLink></template>
      </I18nT>
    </SignInPrompt>
    <LoadError v-else-if="error" :what="t('sessions.what')" :error="error" @retry="loadSession(String(route.params.id))"/>
    <NotFound v-else-if="session === null" :title="t('sessions.notFoundTitle')">{{ t('sessions.notFound') }}</NotFound>
    <LoadingRows v-else-if="!session" :rows="6" :label="t('sessions.loading')"/>

    <!-- What it is, and where and when at the right, its episodes and activity under it; on phones the facts after its
         name -->
    <div v-else class="workspace">
      <div class="lead">
        <header ref="titleBlock" class="title">
          <!-- What it's called, else its date; the team renames it in place -->
          <RenameSession v-if="renaming" :session="session" @renamed="renamed" @close="stopRenaming"/>
          <div v-else class="named">
            <h1 v-if="sessionName(session)" lang="sl">{{ sessionName(session) }}</h1>
            <h1 v-else><time :datetime="session.date">{{ formatDate(session.date, dateLocale) }}</time></h1>
            <button v-if="auth.team" ref="renameButton" type="button" class="text-button rename" @click="renaming = true">
              {{ session.name ? t('sessions.rename') : t('sessions.nameIt') }}
            </button>
          </div>
          <p class="meta">
            <span>{{ t('sessions.kind') }}<template v-if="sessionName(session)">
              · <time :datetime="session.date">{{ formatDate(session.date, dateLocale) }}</time>
              <template v-if="sessionPlace(session)"> · {{ sessionPlace(session) }}</template>
            </template></span>
          </p>
        </header>
      </div>

      <FactPanel :label="t('sessions.about')">
        <PanelTitle :title="sessionTitle(session, dateLocale)" :shown="pastTitle"/>
        <FactGroup>
          <dl>
            <Fact :label="t('sessions.when')">
              <time :datetime="session.date">{{ formatDate(session.date, dateLocale) }}</time>
              <span class="aside"> · {{ relativeDate(session.date, dateLocale) }}</span>
            </Fact>
            <Fact v-if="session.location" :label="t('sessions.location')">{{ session.location }}</Fact>
            <Fact v-if="session.address" :label="t('sessions.address')">{{ session.address }}</Fact>
          </dl>
        </FactGroup>
        <FactGroup v-if="hasFiles" :title="t('sessions.files')">
          <dl>
            <Fact v-if="session.documents.length" :label="t('sessions.docs')"><span class="icons"><DocLinks :documents="session.documents"/></span></Fact>
            <Fact v-if="session.koofrFolders.length" label="Koofr" stacked><KoofrFolderList :folders="session.koofrFolders"/></Fact>
            <Fact v-if="session.dropboxFolders.length" label="Dropbox" stacked><DropboxFolderList :folders="session.dropboxFolders"/></Fact>
          </dl>
        </FactGroup>
      </FactPanel>

      <div class="main">
        <section aria-labelledby="episodes">
          <div class="heading">
            <h2 id="episodes">{{ t('sessions.episodes') }}</h2>
            <span class="count">{{ session.episodes.length }}</span>
          </div>
          <p v-if="!session.episodes.length" class="note">{{ t('sessions.noEpisodes') }}</p>
          <!-- Each as the other lists have it: a line while it's in the works, its summary once it's released -->
          <ul v-else class="episodes">
            <template v-for="episode in session.episodes" :key="episode.id">
              <EpisodeItem v-if="episode.status === 'PUBLISHED'" :episode="episode"/>
              <EpisodeRow v-else :episode="episode" under-session/>
            </template>
          </ul>
        </section>

        <PhotoStrip v-if="photos.length" :title="t('sessions.photos')" :photos="photos" :folder="session.koofrFolders[0]?.url" service="Koofr"/>

        <ActivityFeed :comments="session.comments" :changes="session.history" :session-id="session.id"
                      @changed="reloadQuietly(session.id)"/>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// Its name, and the team's Rename after it, quiet as a section's links at a heading's end
.named {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: 12px;
}

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
