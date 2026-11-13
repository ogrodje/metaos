<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { errorMessage, graphql } from '@/graphql'
import { kindLabel, type MediaKind } from '@/media'
import {
  defaultFile, EPISODE_FILES, type EpisodeFiles, hasTranscript, languageOf, languages, providerName, type SpeechProvider,
  TRANSCRIBE, type Transcription,
} from '@/transcriptions'
import { useLocale } from '@/useLocale'

// Transcribing one of an episode's files, as transcribeEpisode does, as transcribe_episode does over MCP: the episode
// by its code, which finds its files; the file, its full audio unless another is chosen; the provider; and the language,
// Slovenian or English, its show's to begin with. Batch is Google's; replacing, for a file the provider has transcribed
// already.
const props = defineProps<{ providers: SpeechProvider[] }>()
const emit = defineEmits<{ started: [transcription: Transcription] }>()
const { t } = useLocale()

const typed = ref('')

// Opened from the page's heading, it's for typing the episode in
const field = ref<HTMLInputElement>()
onMounted(() => field.value?.focus())
const episode = ref<EpisodeFiles | null>()
const finding = ref(false)
const media = ref('')
const provider = ref('')
const language = ref('sl-SI')
const batch = ref(false)
const replace = ref(false)
const busy = ref(false)
const error = ref<string>()

watch(() => props.providers, (providers) => {
  if (!providers.some((p) => p.name === provider.value)) provider.value = providers[0]?.name ?? ''
}, { immediate: true })

async function find() {
  const code = typed.value.trim()
  if (!code || finding.value) return
  finding.value = true
  error.value = undefined
  try {
    const data = await graphql<{ episode: EpisodeFiles | null }>(EPISODE_FILES, { ref: code })
    episode.value = data.episode
    media.value = data.episode ? defaultFile(data.episode)?.id ?? data.episode.media[0]?.id ?? '' : ''
    if (data.episode) language.value = languageOf(data.episode)
    if (!data.episode) error.value = t('transcriptions.form.noEpisode', { code })
    else if (!data.episode.media.length) {
      error.value = t('transcriptions.form.noMedia', { episode: data.episode.code ?? data.episode.title })
    }
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    finding.value = false
  }
}

const replacing = computed(() => !!episode.value && !!media.value && hasTranscript(episode.value, media.value, provider.value))

async function transcribe() {
  if (busy.value || !episode.value || !media.value) return
  busy.value = true
  error.value = undefined
  try {
    const { transcription } = await graphql<{ transcription: Transcription }>(TRANSCRIBE, {
      ref: episode.value.code ?? episode.value.id,
      media: media.value,
      provider: provider.value || null,
      language: language.value,
      batch: provider.value === 'google' && batch.value,
      // As it's ticked; MetaOS says if the file has a transcript by the provider, which may have changed since
      replace: replace.value,
    })
    emit('started', transcription)
    episode.value = undefined
    typed.value = ''
    language.value = 'sl-SI'
    batch.value = false
    replace.value = false
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="transcribe" :aria-label="t('transcriptions.transcribeEpisode')">
    <form class="fields" @submit.prevent="episode ? transcribe() : find()">
      <label class="field episode">
        <span class="label">{{ t('transcriptions.form.episode') }}</span>
        <input ref="field" v-model="typed" class="input" type="text" :placeholder="t('transcriptions.form.episodePlaceholder')"
               autocomplete="off" spellcheck="false"
               @change="find" @input="episode = undefined">
      </label>

      <template v-if="episode && episode.media.length">
        <label class="field">
          <span class="label">{{ t('transcriptions.form.file') }}</span>
          <select v-model="media" class="input">
            <option v-for="file in episode.media" :key="file.id" :value="file.id">
              {{ kindLabel(file.kind as MediaKind, t) }} · {{ file.name }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">{{ t('transcriptions.form.provider') }}</span>
          <select v-model="provider" class="input">
            <option v-for="p in providers" :key="p.name" :value="p.name">{{ providerName(p.name) }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">{{ t('transcriptions.form.language') }}</span>
          <select v-model="language" class="input">
            <option v-for="l in languages(t)" :key="l.value" :value="l.value">{{ l.title }}</option>
          </select>
        </label>
      </template>

      <button v-if="!episode" type="submit" class="button" :aria-disabled="finding || !typed.trim() || undefined">
        {{ finding ? t('transcriptions.form.finding') : t('transcriptions.form.find') }}
      </button>
      <button v-else-if="episode.media.length" type="submit" class="button primary" :aria-disabled="busy || undefined">
        {{ busy ? t('transcriptions.form.starting') : t('transcriptions.form.transcribe') }}
      </button>
    </form>

    <div v-if="episode && episode.media.length" class="options">
      <label v-if="provider === 'google'" class="check">
        <input v-model="batch" type="checkbox"> {{ t('transcriptions.form.batch') }}
      </label>
      <label v-if="replacing" class="check">
        <input v-model="replace" type="checkbox"> {{ t('transcriptions.form.replace', { provider: providerName(provider) }) }}
      </label>
      <p class="hint">
        <span lang="sl">{{ episode.code ?? '' }} {{ episode.title }}</span>. {{ t('transcriptions.form.hint') }}
      </p>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped lang="scss">
// Under the page's heading, which opens it: a row of fields, not a box
.transcribe {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;

  &.episode {
    width: 160px;
  }
}

.label {
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);
}

select {
  max-width: 320px;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;
}

.hint {
  margin: 0;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

.error {
  margin: 0;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

.button[aria-disabled='true'] {
  cursor: progress;
}
</style>
