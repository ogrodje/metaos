<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { I18nT } from 'vue-i18n'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import ChipGroup from '@/components/ChipGroup.vue'
import FilterBar from '@/components/FilterBar.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import PageHeader from '@/components/PageHeader.vue'
import SelectFilter from '@/components/SelectFilter.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import YouTubeUploadRow from '@/components/YouTubeUploadRow.vue'
import YouTubeVideoRow from '@/components/YouTubeVideoRow.vue'
import { errorMessage, graphql } from '@/graphql'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'
import {
  CHANNEL, type ChannelUpload, type ChannelVideo, type EpisodeFilter, filterVideos, inEpisode, inKind, inPrivacy,
  type KindFilter, type PrivacyFilter, REFRESH, shownUploads, type YouTubeChannel,
} from '@/youtubeChannel'

// Ogrodje's YouTube channel, the team's: every video MetaOS knows there, the latest first, with who may see it and
// whose it is, and the uploads of episodes' files. MetaOS looks at the channel every hour; Refresh looks now. Videos
// are sent from an episode's page (Video and audio), always as private ones, and published in YouTube Studio.
const auth = useAuthStore()
const { t, path } = useLocale()

const channel = ref<YouTubeChannel>()
const videos = ref<ChannelVideo[]>()
const uploads = ref<ChannelUpload[]>([])
const error = ref<unknown>()

// Only the latest load counts: an earlier one may answer after it
let loads = 0

// The team's, so it loads only for staff. Quietly as it changes (live updates: a look at the channel, an upload going
// on): what's shown stays until the new is there, and stays if it doesn't come.
async function load(quietly = false) {
  const load = ++loads
  if (!quietly) {
    videos.value = undefined
    error.value = undefined
  }
  if (!auth.team) return
  try {
    const data = await graphql<{ youtube: YouTubeChannel, youtubeVideos: ChannelVideo[], youtubeUploads: ChannelUpload[] }>(CHANNEL)
    if (load !== loads) return
    channel.value = data.youtube
    videos.value = keepUnchanged(videos.value, data.youtubeVideos)
    uploads.value = keepUnchanged(uploads.value, data.youtubeUploads)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = e
  }
}

watch(() => auth.team, () => void load(), { immediate: true })
useLiveRefresh(() => load(true))

// Looking at the channel now: a workflow, whose changes the live updates bring; the button waits for it to start
const refreshing = ref(false)
const refreshError = ref<string>()

async function refresh() {
  if (refreshing.value) return
  refreshing.value = true
  refreshError.value = undefined
  try {
    const data = await graphql<{ youtube: YouTubeChannel }>(REFRESH)
    channel.value = data.youtube
    // What it found is there in seconds; the live updates say so too, but for a look that changed nothing
    setTimeout(() => void load(true).catch(() => undefined), 4000)
  } catch (e) {
    refreshError.value = errorMessage(e, t)
  } finally {
    refreshing.value = false
  }
}

// Which, as the address says, e.g. ?kind=video&privacy=private&episode=without
const kind = useQueryState<KindFilter>('kind', 'all', ['all', 'video', 'short'])
const privacy = useQueryState<PrivacyFilter>('privacy', 'all', ['all', 'public', 'unlisted', 'private'])
const episode = useQueryState<EpisodeFilter>('episode', 'all', ['all', 'with', 'without'])
const find = useQueryState('q', '')
const clear = useClearQuery()

const all = computed(() => videos.value ?? [])
const filtering = computed(() => kind.value !== 'all' || privacy.value !== 'all' || episode.value !== 'all' || find.value.trim() !== '')

// Each chip with how many it has; one with none is left out, unless it's the one chosen
function options<T extends string>(values: readonly T[], chosen: T, title: (value: T) => string, has: (video: ChannelVideo, value: T) => boolean) {
  return values.map((value) => ({ value, title: title(value), count: all.value.filter((video) => has(video, value)).length }))
    .filter((option) => option.value === 'all' || option.count || option.value === chosen)
}

const kinds = computed(() => options(['all', 'video', 'short'] as const, kind.value, (value) => t(`channel.filter.kind.${value}`), inKind))
const privacies = computed(() => options(['all', 'public', 'unlisted', 'private'] as const, privacy.value,
  (value) => t(`channel.filter.privacy.${value}`), inPrivacy))
const episodes = computed(() => options(['all', 'with', 'without'] as const, episode.value, (value) => t(`channel.filter.episode.${value}`), inEpisode))

const shown = computed(() => filterVideos(all.value, { kind: kind.value, privacy: privacy.value, episode: episode.value, find: find.value }))
const sent = computed(() => shownUploads(uploads.value))
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('channel.signIn.title')">
      <I18nT keypath="channel.signIn.text" scope="global">
        <template #usage><RouterLink class="usage" :to="path('/usage')">{{ t('pages.usage') }}</RouterLink></template>
      </I18nT>
    </SignInPrompt>

    <template v-else>
      <PageHeader :title="t('pages.youtube')" :count="videos?.length" :shown="filtering && videos ? shown.length : undefined">
        {{ channel?.handle ? t('channel.aboutChannel', { channel: channel.handle }) : t('channel.about') }}
        <template v-if="auth.team && channel?.connected" #actions>
          <button type="button" class="button" :aria-disabled="refreshing || channel.refreshing || undefined" @click="refresh">
            {{ refreshing || channel.refreshing ? t('channel.refreshing') : t('channel.refresh') }}
          </button>
        </template>
      </PageHeader>

      <!-- Signed in as a member, not staff -->
      <p v-if="auth.ready && !auth.team" class="note">{{ t('channel.teamOnly') }}</p>

      <template v-else>
        <p v-if="channel && !channel.connected" class="note problem" role="status">
          {{ channel.error ? t('channel.notConnectedWhy', { error: channel.error }) : t('channel.notConnected') }}
        </p>
        <p v-if="refreshError" class="note problem" role="alert">{{ t('channel.refreshFailed', { error: refreshError }) }}</p>

        <section v-if="sent.on.length || sent.ended.length" aria-labelledby="uploads-heading">
          <div class="heading">
            <h2 id="uploads-heading">{{ t('channel.uploads') }}</h2>
            <span class="note">{{ t('channel.uploadsNote') }}</span>
          </div>
          <ul class="list">
            <YouTubeUploadRow v-for="upload in [...sent.on, ...sent.ended]" :key="upload.id" :upload="upload" @changed="load(true)"/>
          </ul>
        </section>

        <!-- As the Events page's: the box, the chips, and the rest as selects at the end, in one row -->
        <FilterBar v-model:find="find" :placeholder="t('channel.filter.find')">
          <ChipGroup v-if="kinds.length > 2" v-model="kind" :label="t('channel.filter.kindLabel')" :options="kinds"/>
          <template #end>
            <SelectFilter v-if="privacies.length > 2" v-model="privacy" :label="t('channel.filter.privacyLabel')" :options="privacies"/>
            <SelectFilter v-model="episode" :label="t('channel.filter.episodeLabel')" :options="episodes"/>
          </template>
        </FilterBar>

        <LoadError v-if="error" :what="t('channel.what')" :error="error" @retry="load()"/>
        <LoadingRows v-else-if="!videos" :rows="8" :label="t('channel.loading')"/>
        <p v-else-if="!videos.length" class="note">{{ channel?.connected ? t('channel.none') : t('channel.noneNotConnected') }}</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['kind', 'privacy', 'episode', 'q'])">
          {{ t('channel.noMatch') }}
        </NoMatch>
        <ul v-else class="list">
          <YouTubeVideoRow v-for="video in shown" :key="video.id" :video="video" @changed="load(true)"/>
        </ul>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.heading .note {
  margin-left: 4px;
}

// The channel can't be asked, or a look at it didn't start
.problem {
  color: var(--accent-text);
}

// API and MCP usage, which is in the footer, from the sign-in prompt's words
.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}
</style>
