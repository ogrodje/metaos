<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { RouterLink } from 'vue-router'
import { faArrowUpRightFromSquare } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { errorMessage, graphql } from '@/graphql'
import { useLocale } from '@/useLocale'
import { privacyName } from '@/youtube'
import {
  type ChannelVideo, detailsRefusal, formatDay, MAX_TITLE, SET_EPISODE, tagsOf, tagsText, UPDATE_VIDEO, videoLine,
} from '@/youtubeChannel'

// One of the channel's videos: its thumbnail, its title, which opens it on YouTube, what it is and who may see it, and
// whose it is in MetaOS. The team says whose it is (Episode), and changes what it says about itself on YouTube (Edit:
// its title, description and tags), each asked under its row. Never who may see it: that's YouTube Studio's.
const props = defineProps<{ video: ChannelVideo }>()
const emit = defineEmits<{ changed: [] }>()
const { t, dateLocale, path } = useLocale()
const id = useId()

const episodeLink = computed(() => (props.video.episode ? path(`/episodes/${props.video.episode.code ?? props.video.episode.id}`) : null))

// YouTube's thumbnail; one that doesn't load, e.g. a private video's whose address ran out, is the grey of a missing picture
const pictureFailed = ref(false)

// What's open under the row, if anything
const open = ref<'episode' | 'edit'>()
const busy = ref(false)
const error = ref<string>()

const code = ref('')
const title = ref('')
const description = ref('')
const tags = ref('')
const first = ref<HTMLInputElement>()

async function show(which: 'episode' | 'edit') {
  if (open.value === which) return close()
  error.value = undefined
  code.value = props.video.episode?.code ?? ''
  title.value = props.video.title
  description.value = props.video.description
  tags.value = tagsText(props.video.tags)
  open.value = which
  await nextTick()
  first.value?.focus()
}

function close() {
  open.value = undefined
  error.value = undefined
}

async function act(query: string, variables: Record<string, unknown>) {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await graphql(query, variables)
    emit('changed')
    close()
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

const setEpisode = () => (code.value.trim() ? act(SET_EPISODE, { id: props.video.id, episode: code.value.trim() }) : undefined)
const clearEpisode = () => act(SET_EPISODE, { id: props.video.id, episode: null })

// Only what changed is sent, so a title left as it is isn't written again
function save() {
  const details = { title: title.value, description: description.value, tags: tagsOf(tags.value) }
  const refused = detailsRefusal(details, t)
  if (refused) return void (error.value = refused)
  const changed = {
    title: details.title.trim() !== props.video.title ? details.title.trim() : null,
    description: details.description.trim() !== props.video.description ? details.description.trim() : null,
    tags: tagsText(details.tags) !== tagsText(props.video.tags) ? details.tags : null,
  }
  if (changed.title == null && changed.description == null && changed.tags == null) return close()
  return act(UPDATE_VIDEO, { id: props.video.id, ...changed })
}
</script>

<template>
  <li class="video" :class="video.privacy.toLowerCase()">
    <a class="picture" :href="video.url" target="_blank" rel="noopener" tabindex="-1" aria-hidden="true">
      <img v-if="video.thumbnailUrl && !pictureFailed" :src="video.thumbnailUrl" alt="" loading="lazy" decoding="async"
           referrerpolicy="no-referrer" width="112" height="63" @error="pictureFailed = true">
    </a>

    <div class="what">
      <a class="name" :href="video.url" target="_blank" rel="noopener" lang="sl">{{ video.title }}</a>
      <span class="line">{{ videoLine(video, t, dateLocale) }}</span>
    </div>

    <div class="who">
      <span class="privacy">{{ privacyName(video.privacy, t) }}</span>
      <span v-if="video.publishAt" class="scheduled">{{ t('channel.scheduled', { day: formatDay(video.publishAt, dateLocale) }) }}</span>
    </div>

    <div class="whose">
      <template v-if="video.episode && episodeLink">
        <span v-if="video.episode.code" class="code">{{ video.episode.code }}</span>
        <RouterLink :to="episodeLink" class="episode" lang="sl">{{ video.episode.title }}</RouterLink>
      </template>
      <span v-else class="none">{{ t('channel.noEpisode') }}</span>
    </div>

    <div class="actions">
      <button type="button" class="button small" :aria-expanded="open === 'episode'" :aria-controls="`${id}-episode`"
              @click="show('episode')">{{ t('channel.episode.button') }}</button>
      <button type="button" class="button small" :aria-expanded="open === 'edit'" :aria-controls="`${id}-edit`"
              @click="show('edit')">{{ t('common.edit') }}</button>
      <a class="button small quiet" :href="video.studioUrl" target="_blank" rel="noopener" :aria-label="t('youtube.studio')">
        Studio<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
    </div>

    <!-- Whose it is in MetaOS: an episode by its code, or no one's -->
    <form v-if="open === 'episode'" :id="`${id}-episode`" class="form" :aria-label="t('channel.episode.form', { title: video.title })"
          @submit.prevent="setEpisode" @keydown.esc="close">
      <label :for="`${id}-code`">{{ t('channel.episode.code') }}</label>
      <div class="inline">
        <input :id="`${id}-code`" ref="first" v-model="code" class="input" type="text" autocomplete="off" spellcheck="false"
               :placeholder="t('transcriptions.form.episodePlaceholder')" :aria-describedby="`${id}-code-hint`">
        <button type="submit" class="button primary small" :aria-disabled="busy || !code.trim() || undefined">{{ t('common.save') }}</button>
        <button v-if="video.episode" type="button" class="button small" :aria-disabled="busy || undefined" @click="clearEpisode">
          {{ t('channel.episode.clear') }}
        </button>
        <button type="button" class="button small quiet" @click="close">{{ t('common.cancel') }}</button>
      </div>
      <p :id="`${id}-code-hint`" class="hint">{{ t('channel.episode.hint') }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </form>

    <!-- What it says about itself on YouTube -->
    <form v-if="open === 'edit'" :id="`${id}-edit`" class="form" :aria-label="t('channel.edit.form', { title: video.title })"
          @submit.prevent="save" @keydown.esc="close">
      <p v-if="video.privacy !== 'PRIVATE'" class="warning">{{ t('channel.edit.seen') }}</p>
      <label :for="`${id}-title`">{{ t('youtube.title') }}</label>
      <input :id="`${id}-title`" ref="first" v-model="title" class="input" type="text" :maxlength="MAX_TITLE" autocomplete="off">
      <label :for="`${id}-description`">{{ t('youtube.description') }}</label>
      <textarea :id="`${id}-description`" v-model="description" class="input" rows="8" :aria-describedby="`${id}-description-hint`"/>
      <p :id="`${id}-description-hint`" class="hint">{{ t('channel.edit.descriptionHint') }}</p>
      <label :for="`${id}-tags`">{{ t('channel.edit.tags') }}</label>
      <input :id="`${id}-tags`" v-model="tags" class="input" type="text" autocomplete="off" spellcheck="false"
             :aria-describedby="`${id}-tags-hint`">
      <p :id="`${id}-tags-hint`" class="hint">{{ t('channel.edit.tagsHint') }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="inline">
        <button type="submit" class="button primary small" :aria-disabled="busy || undefined">
          {{ busy ? t('channel.edit.saving') : t('common.save') }}
        </button>
        <button type="button" class="button small quiet" :disabled="busy" @click="close">{{ t('common.cancel') }}</button>
      </div>
    </form>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/pictures';

// Columns at the frame's width, the same in every row: its picture, what it is, who may see it, whose it is, and what
// may be done; on phones the picture beside the rest, stacked
.video {
  display: grid;
  grid-template-columns: 112px minmax(0, 3fr) 110px minmax(0, 2fr) 216px;
  column-gap: 16px;
  row-gap: 4px;
  align-items: start;
  padding: 11px 0;
  border-top: 1px solid var(--rule);
}

// YouTube's thumbnail, 16:9, as a thing's picture is; grey while there's none
.picture {
  display: block;
  width: 112px;
  aspect-ratio: 16 / 9;
  @include pictures.picture;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.what, .who, .whose {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: var(--text-small);
  line-height: 20px;
}

.name, .episode {
  color: var(--ink);
  overflow-wrap: anywhere;

  &:hover {
    color: var(--accent-text);
  }
}

.name {
  font-size: var(--text-body);
  font-weight: 500;
}

.line, .scheduled, .none {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.privacy {
  color: var(--secondary);
}

// What isn't out stands out: a private or unlisted video is what's left to publish
.private .privacy, .unlisted .privacy {
  font-weight: 500;
  color: var(--accent-text);
}

.code {
  font-family: var(--font-mono);
  color: var(--muted);
}

.actions, .inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.actions {
  justify-content: flex-end;

  a {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
}

.external {
  width: 10px;
  height: 10px;
}

// Asked under the row, from its second column, as wide as running text
.form {
  grid-column: 2 / -1;
  display: grid;
  gap: 4px;
  max-width: 620px;
  padding: 6px 0 4px;
  font-size: var(--text-small);
  line-height: 20px;

  label {
    margin-top: 6px;
    font-weight: 600;
  }

  textarea {
    height: auto;
    padding-block: 6px;
    line-height: 20px;
    resize: vertical;
  }

  .inline {
    margin-top: 6px;

    .input {
      flex: 0 1 160px;
      min-width: 120px;
    }
  }
}

.hint {
  color: var(--muted);
}

.warning {
  font-weight: 500;
  color: var(--accent-text);
}

.error {
  color: var(--accent-text);
}

@media (max-width: 860px) {
  .video {
    grid-template-columns: 112px minmax(0, 1fr);
  }

  .what, .who, .whose, .actions, .form {
    grid-column: 2;
  }

  .actions {
    justify-content: flex-start;
  }
}

@media (max-width: 480px) {
  .video {
    grid-template-columns: 80px minmax(0, 1fr);
  }

  .picture {
    width: 80px;
  }
}
</style>
