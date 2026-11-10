<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { faArrowRotateRight } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { type Channel, CHANNELS, channelOf, distributedAt, type Distribution, distributionTitle, LINK_NAMES } from '@/distribution'
import { addDistribution, refreshPreview, removeDistribution, updateDistribution } from '@/distributionChanges'
import { formatDate, TIME_ZONE } from '@/episodes'
import { errorMessage } from '@/graphql'
import { src, srcset } from '@/images'

// Where an episode went out, for the team: first its own links, which follow the episode's (Released on), then the
// posts that promote it, the latest first, each as its page's preview says it (its picture, title and site), with when
// it went out, who added it and a note. A post is logged by its URL, its channel told by its host. Removing one asks
// first, on its row; removing a link clears it on the episode too. Under them, a form to log another.
const props = defineProps<{
  episodeId: string
  // When it aired, which its links went out with
  airedDate: string | null
  distributions: Distribution[]
}>()

const emit = defineEmits<{
  // One was added, changed or removed
  changed: []
}>()

const id = useId()

// Those changed here, as they are now, until the page has loaded again; and those removed, gone at once
const changed = ref<Record<string, Distribution>>({})
const removed = ref<string[]>([])

const entries = computed(() => props.distributions
  .filter((entry) => !removed.value.includes(entry.id))
  .map((entry) => changed.value[entry.id] ?? entry))
const links = computed(() => entries.value.filter((entry) => entry.platform))
const posts = computed(() => entries.value.filter((entry) => !entry.platform))

function kept(entry: Distribution) {
  changed.value = { ...changed.value, [entry.id]: entry }
}

// As the date field has it, a day in Ljubljana
function day(at: string | null) {
  return at ? new Date(at).toLocaleDateString('sv-SE', { timeZone: TIME_ZONE }) : ''
}

function siteOf(entry: Distribution) {
  if (entry.platform) return LINK_NAMES[entry.platform]
  return entry.channel === 'OTHER' && entry.preview.site ? entry.preview.site : CHANNELS[entry.channel].label
}

// A new post, its channel as its URL says it, unless chosen
const url = ref('')
const channel = ref<Channel | ''>('')
const postedAt = ref('')
const note = ref('')
const adding = ref(false)
const addError = ref<string>()
const detected = computed(() => channelOf(url.value))
const chosen = computed<Channel | null>(() => channel.value || detected.value)

async function add() {
  if (adding.value || !url.value.trim()) return
  adding.value = true
  addError.value = undefined
  try {
    const added = await addDistribution(props.episodeId, url.value.trim(), {
      channel: channel.value || null, postedAt: postedAt.value, note: note.value,
    })
    kept(added)
    url.value = ''
    channel.value = ''
    postedAt.value = ''
    note.value = ''
    emit('changed')
  } catch (e) {
    addError.value = `It wasn't added: ${errorMessage(e)}`
  } finally {
    adding.value = false
  }
}

// One at a time: editing, or asked to be removed
const editing = ref<string>()
const confirming = ref<string>()
const busy = ref<string>()
const error = ref<{ id: string, message: string }>()
const form = ref({ url: '', title: '', note: '', postedAt: '', channel: 'OTHER' as Channel })

function edit(entry: Distribution) {
  error.value = undefined
  confirming.value = undefined
  editing.value = entry.id
  form.value = { url: entry.url, title: entry.title ?? '', note: entry.note ?? '', postedAt: day(entry.postedAt), channel: entry.channel }
  void nextTick(() => document.getElementById(`${id}-title-${entry.id}`)?.focus())
}

function stopEditing(entry: Distribution) {
  editing.value = undefined
  void nextTick(() => document.getElementById(`${id}-edit-${entry.id}`)?.focus())
}

async function save(entry: Distribution) {
  if (busy.value) return
  busy.value = entry.id
  error.value = undefined
  try {
    kept(await updateDistribution(entry.id, {
      ...(form.value.url.trim() !== entry.url ? { url: form.value.url.trim() } : {}),
      ...(!entry.platform && form.value.channel !== entry.channel ? { channel: form.value.channel } : {}),
      title: form.value.title,
      note: form.value.note,
      postedAt: form.value.postedAt,
    }))
    stopEditing(entry)
    emit('changed')
  } catch (e) {
    error.value = { id: entry.id, message: `It wasn't saved: ${errorMessage(e)}` }
  } finally {
    busy.value = undefined
  }
}

async function refresh(entry: Distribution) {
  if (busy.value) return
  busy.value = entry.id
  error.value = undefined
  try {
    kept(await refreshPreview(entry.id))
  } catch (e) {
    error.value = { id: entry.id, message: `Its preview wasn't fetched again: ${errorMessage(e)}` }
  } finally {
    busy.value = undefined
  }
}

function askToRemove(entry: Distribution) {
  error.value = undefined
  editing.value = undefined
  confirming.value = entry.id
  void nextTick(() => document.getElementById(`${id}-keep-${entry.id}`)?.focus())
}

function keep(entry: Distribution) {
  confirming.value = undefined
  void nextTick(() => document.getElementById(`${id}-remove-${entry.id}`)?.focus())
}

async function remove(entry: Distribution) {
  if (busy.value) return
  busy.value = entry.id
  try {
    await removeDistribution(entry.id)
    removed.value.push(entry.id)
    confirming.value = undefined
    emit('changed')
  } catch (e) {
    error.value = { id: entry.id, message: `It wasn't removed: ${errorMessage(e)}` }
  } finally {
    busy.value = undefined
  }
}

const groups = computed(() => [
  { key: 'links', title: 'Released on', entries: links.value },
  { key: 'posts', title: 'Posts', entries: posts.value },
].filter((group) => group.entries.length))
</script>

<template>
  <div class="distribution">

    <section v-for="group in groups" :key="group.key" class="group" :aria-labelledby="`${id}-${group.key}`">
      <h3 :id="`${id}-${group.key}`">{{ group.title }}<span class="count">{{ group.entries.length }}</span></h3>
      <ul class="entries">
        <li v-for="entry in group.entries" :key="entry.id" class="entry">
          <a class="thumb" :href="entry.url" target="_blank" rel="noopener" tabindex="-1" aria-hidden="true">
            <img v-if="entry.preview.image" :src="src(entry.preview.image, 192)" :srcset="srcset(entry.preview.image)"
                 sizes="96px" alt="" loading="lazy">
            <FontAwesomeIcon v-else :icon="CHANNELS[entry.channel].icon" :style="{ color: CHANNELS[entry.channel].color }"/>
          </a>
          <span class="text">
            <span class="site">
              <FontAwesomeIcon :icon="CHANNELS[entry.channel].icon" :style="{ color: CHANNELS[entry.channel].color }"/>
              {{ siteOf(entry) }}
            </span>
            <a class="title" :href="entry.url" target="_blank" rel="noopener">{{ distributionTitle(entry) }}</a>
            <span v-if="entry.preview.description" class="description">{{ entry.preview.description }}</span>
            <span class="by">
              <time :datetime="distributedAt(entry, airedDate)">{{ formatDate(distributedAt(entry, airedDate)) }}</time><template
                v-if="entry.addedBy"> · {{ entry.addedBy }}</template><template v-if="entry.note"> · {{ entry.note }}</template>
            </span>
            <span v-if="entry.preview.status === 'PENDING'" class="state">Fetching its preview…</span>
            <span v-else-if="entry.preview.status === 'UNAVAILABLE'" class="state">No preview: {{ entry.preview.error }}</span>
            <span v-if="error?.id === entry.id" class="error" role="alert">{{ error.message }}</span>

            <form v-if="editing === entry.id" class="edit" @submit.prevent="save(entry)" @keydown.esc="stopEditing(entry)">
              <label>
                <span class="label">Title</span>
                <input :id="`${id}-title-${entry.id}`" v-model="form.title" class="input small" type="text"
                       :placeholder="entry.preview.title ?? 'As its page says'">
              </label>
              <label>
                <span class="label">Link</span>
                <input v-model="form.url" class="input small" type="url" required>
              </label>
              <label v-if="!entry.platform">
                <span class="label">On</span>
                <select v-model="form.channel" class="input small">
                  <option v-for="(about, key) in CHANNELS" :key="key" :value="key">{{ about.label }}</option>
                </select>
              </label>
              <label>
                <span class="label">When</span>
                <input v-model="form.postedAt" class="input small" type="date">
              </label>
              <label class="note">
                <span class="label">Note</span>
                <input v-model="form.note" class="input small" type="text" maxlength="2000">
              </label>
              <span class="buttons">
                <button type="submit" class="button small primary" :aria-disabled="busy === entry.id || undefined">
                  {{ busy === entry.id ? 'Saving…' : 'Save' }}
                </button>
                <button type="button" class="button small quiet" @click="stopEditing(entry)">Cancel</button>
              </span>
            </form>
          </span>
          <span v-if="confirming === entry.id" class="confirm" role="group" :aria-labelledby="`${id}-ask-${entry.id}`"
                @keydown.esc="keep(entry)">
            <span :id="`${id}-ask-${entry.id}`" class="question">
              {{ entry.platform ? `Remove it, and clear the episode's ${LINK_NAMES[entry.platform]} link?` : 'Remove it?' }}
            </span>
            <button type="button" class="button small danger" :aria-disabled="busy === entry.id || undefined" @click="remove(entry)">
              {{ busy === entry.id ? 'Removing…' : 'Remove' }}
            </button>
            <button :id="`${id}-keep-${entry.id}`" type="button" class="button small" :disabled="busy === entry.id" @click="keep(entry)">Cancel</button>
          </span>
          <span v-else-if="editing !== entry.id" class="actions">
            <button type="button" class="button small quiet" :aria-disabled="busy === entry.id || undefined"
                    :aria-label="`Fetch the preview of ${distributionTitle(entry)} again`" title="Fetch its preview again"
                    @click="refresh(entry)">
              <FontAwesomeIcon :icon="faArrowRotateRight"/>Refresh
            </button>
            <button :id="`${id}-edit-${entry.id}`" type="button" class="button small quiet" @click="edit(entry)">Edit</button>
            <button :id="`${id}-remove-${entry.id}`" type="button" class="button small quiet" @click="askToRemove(entry)">Remove</button>
          </span>
        </li>
      </ul>
    </section>

    <form class="add" :aria-labelledby="`${id}-add`" @submit.prevent="add">
      <h3 :id="`${id}-add`">Log where it went out</h3>
      <div class="fields">
        <label class="url">
          <span class="label">Link to the post</span>
          <span class="with-channel">
            <input v-model="url" class="input" type="url" inputmode="url" placeholder="https://www.linkedin.com/posts/…"
                   autocomplete="off" required>
            <FontAwesomeIcon v-if="chosen && url.trim()" class="detected" :icon="CHANNELS[chosen].icon"
                             :style="{ color: CHANNELS[chosen].color }" :title="CHANNELS[chosen].label"/>
          </span>
        </label>
        <label>
          <span class="label">On</span>
          <select v-model="channel" class="input">
            <option value="">{{ detected ? `${CHANNELS[detected].label}, by its link` : 'By its link' }}</option>
            <option v-for="(about, key) in CHANNELS" :key="key" :value="key">{{ about.label }}</option>
          </select>
        </label>
        <label>
          <span class="label">When</span>
          <input v-model="postedAt" class="input" type="date">
        </label>
        <label class="note">
          <span class="label">Note</span>
          <input v-model="note" class="input" type="text" placeholder="e.g. posted by Iva in #announcements" maxlength="2000">
        </label>
        <button type="submit" class="button primary" :aria-disabled="adding || !url.trim() || undefined">
          {{ adding ? 'Adding…' : 'Add' }}
        </button>
      </div>
      <p v-if="addError" class="error" role="alert">{{ addError }}</p>
      <p class="hint">MetaOS fetches its preview from the page. The episode's links are here by themselves.</p>
    </form>
  </div>
</template>

<style scoped lang="scss">
@use '../assets/pictures';

.distribution {
  display: grid;
  gap: 18px;
}

h3 {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);

  .count {
    font-weight: 500;
  }
}

.label {
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 500;
  color: var(--secondary);
}

label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

// The link first and widest, then where and when, then a note, and Add at the end of the row
.add .fields {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) 150px;
  align-items: end;
  gap: 8px;

  .note {
    grid-column: 1 / 3;
  }

  .button {
    justify-self: stretch;
    justify-content: center;
  }
}

.with-channel {
  position: relative;
  display: grid;

  .input {
    padding-right: 34px;
  }
}

.detected {
  position: absolute;
  top: 50%;
  right: 10px;
  width: 16px;
  height: 16px;
  transform: translateY(-50%);
}

.hint {
  margin-top: 6px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
}

.entries {
  padding: 0;
  list-style: none;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

.entry {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 12px;

  & + & {
    border-top: 1px solid var(--rule);
  }
}

// Its page's picture, as link previews have them, or its channel's logo in the grey of a picture that isn't there
.thumb {
  @include pictures.picture;
  display: grid;
  place-items: center;
  width: 96px;
  aspect-ratio: 16 / 9;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  svg {
    width: 20px;
    height: 20px;
  }
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
  font-size: var(--text-caption);
  line-height: 18px;
}

.site {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: var(--secondary);

  svg {
    width: 12px;
    height: 12px;
  }
}

.title {
  font-size: var(--text-body);
  line-height: 20px;
  font-weight: 600;
  color: var(--ink);
  overflow-wrap: anywhere;

  &:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}

.description {
  display: -webkit-box;
  overflow: hidden;
  color: var(--secondary);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.by, .state {
  color: var(--muted);
}

.error {
  color: var(--accent-text);
}

.edit {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;

  .note, .buttons {
    grid-column: 1 / -1;
  }

  .buttons {
    display: flex;
    gap: 6px;
  }
}

.actions,
.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.question {
  font-size: var(--text-caption);
  font-weight: 500;
}

@media (max-width: 640px) {
  .add .fields {
    grid-template-columns: minmax(0, 1fr);

    .note {
      grid-column: auto;
    }
  }

  .entry {
    grid-template-columns: 72px minmax(0, 1fr);
  }

  .edit {
    grid-template-columns: minmax(0, 1fr);
  }

  .thumb {
    width: 72px;
  }

  .actions,
  .confirm {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
