<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { faUser } from '@fortawesome/free-regular-svg-icons'
import { faMagnifyingGlass } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import MarkedText from '@/components/MarkedText.vue'
import ShowBadge from '@/components/ShowBadge.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { DATE_LOCALE, episodePath, formatDate, guests, sessionName, sessionPath, sessionPlace, TIME_ZONE } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { personPath, rolesOf } from '@/people'
import {
  type EpisodeHit, episodeMatch, type PersonHit, resultCount, SEARCH_QUERY, type SearchResults, searchPath, searchWords,
  type SessionHit,
} from '@/search'

// Search, over any page: from the header's button, ⌘K or Ctrl+K, or /. A few of each kind, the best first; an episode's
// code is that episode alone, which ↵ opens. See all opens the search page with the rest.

const router = useRouter()
const route = useRoute()

const dialog = ref<HTMLDialogElement>()
const input = ref<HTMLInputElement>()
const text = ref('')
// The results with the text they're for, as what's typed is ahead of them
const found = ref<{ text: string, results: SearchResults }>()
const error = ref<string>()
const selected = ref(0)

// Of each kind
const LIMIT = 5
// How long typing pauses before it's searched
const PAUSE = 150

// Known in the browser only, once mounted; until then the button says Ctrl K, as the server would
const mac = ref(false)

type Item =
  | { kind: 'episode', key: string, path: string, hit: EpisodeHit, exact: boolean }
  | { kind: 'person', key: string, path: string, hit: PersonHit }
  | { kind: 'session', key: string, path: string, hit: SessionHit }

interface Group {
  title: string
  // How many there are of it, when there are more than it shows
  more: number | null
  items: Item[]
}

const words = computed(() => (found.value ? searchWords(found.value.text) : []))

const groups = computed<Group[]>(() => {
  const results = found.value?.results
  if (!results) return []
  const episodeItem = (hit: EpisodeHit, exact = false): Item =>
    ({ kind: 'episode', key: `e${hit.episode.id}`, path: episodePath(hit.episode), hit, exact })
  const more = (shown: number, count: number) => (count > shown ? count : null)
  const all: Group[] = []
  if (results.episode) {
    all.push({ title: 'Episode', more: null, items: [episodeItem({ episode: results.episode, matchedIn: ['CODE'], snippet: null }, true)] })
  }
  if (results.episodes.length) {
    all.push({
      title: results.season != null ? `Season ${results.season}` : 'Episodes',
      more: more(results.episodes.length, results.episodeCount),
      items: results.episodes.map((hit) => episodeItem(hit)),
    })
  }
  if (results.people.length) {
    all.push({
      title: 'People',
      more: more(results.people.length, results.personCount),
      items: results.people.map((hit) => ({ kind: 'person', key: `p${hit.person.id}`, path: personPath(hit.person), hit })),
    })
  }
  if (results.sessions.length) {
    all.push({
      title: 'Recording sessions',
      more: more(results.sessions.length, results.sessionCount),
      items: results.sessions.map((hit) => ({ kind: 'session', key: `s${hit.session.id}`, path: sessionPath(hit.session), hit })),
    })
  }
  return all
})

const items = computed(() => groups.value.flatMap((group) => group.items))
const total = computed(() => (found.value ? resultCount(found.value.results) : 0))

// Where an item is in the list, for the keyboard
const indexOf = (item: Item) => items.value.indexOf(item)

// Searched once typing pauses; only the latest answer counts
let timer: ReturnType<typeof setTimeout> | undefined
let asks = 0

watch(text, (value) => {
  clearTimeout(timer)
  if (!value.trim()) {
    asks++
    found.value = undefined
    error.value = undefined
    return
  }
  timer = setTimeout(() => ask(value), PAUSE)
})

async function ask(value: string) {
  const asked = ++asks
  try {
    const data = await graphql<{ search: SearchResults }>(SEARCH_QUERY, { text: value, limit: LIMIT })
    if (asked !== asks) return
    found.value = { text: value, results: data.search }
    error.value = undefined
    selected.value = 0
  } catch (e) {
    if (asked === asks) error.value = errorMessage(e)
  }
}

function open() {
  if (!dialog.value || dialog.value.open) return
  dialog.value.showModal()
  // What was searched last, ready to be typed over
  nextTick(() => input.value?.select())
}

function close() {
  dialog.value?.close()
}

function go(path: string | ReturnType<typeof searchPath>) {
  close()
  router.push(path)
}

// A result opens here, or with ⌘ or Ctrl, Shift or the middle button in a new tab, as a link would
function onItemClick(event: MouseEvent, path: string) {
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.button === 1) {
    event.preventDefault()
    window.open(router.resolve(path).href, '_blank', 'noopener')
  } else {
    go(path)
  }
}

function seeAll() {
  if (text.value.trim()) go(searchPath(text.value.trim()))
}

function onKeydown(event: KeyboardEvent) {
  const count = items.value.length
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (count) selected.value = (selected.value + (event.key === 'ArrowDown' ? 1 : -1) + count) % count
  } else if (event.key === 'Enter') {
    event.preventDefault()
    // The text may be ahead of the results: then it's what was typed that counts
    const item = found.value?.text === text.value ? items.value[selected.value] : undefined
    if (item) go(item.path)
    else seeAll()
  }
}

// Clicks on the backdrop are on the dialog itself; those in it are on its panel
function onDialogClick(event: MouseEvent) {
  if (event.target === dialog.value) close()
}

watch(selected, () => nextTick(() => document.getElementById(`search-item-${selected.value}`)?.scrollIntoView({ block: 'nearest' })))

// Anywhere, but / not while typing in a field
function onShortcut(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  const typing = !!target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open()
  } else if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
    event.preventDefault()
    open()
  }
}

onMounted(() => {
  mac.value = /Mac|iPhone|iPad/.test(navigator.userAgent)
  window.addEventListener('keydown', onShortcut)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  clearTimeout(timer)
})

// Going somewhere, also with the browser's Back, closes it
watch(() => route.fullPath, close)

// A session as lists name it: its place, or else its date
const shortDate = (date: string) =>
  new Date(date).toLocaleDateString(DATE_LOCALE, { day: 'numeric', month: 'short', timeZone: TIME_ZONE })

// Under an episode's title: why it was found when the title doesn't say, or else its guests
function episodeLine(hit: EpisodeHit) {
  const match = episodeMatch(hit, words.value)
  if (match.text) return match
  return { words: words.value, label: null, text: guests(hit.episode).join(', ') || null }
}

// Under a person's name: their bio when that's what was found, or else what they've been and on what
function personLine(hit: PersonHit) {
  const { person } = hit
  if (hit.matchedIn[0] === 'BIO' && person.bio) return person.bio
  const latest = person.episodes.slice(0, 3).map(({ episode }) => episode.code ?? episode.title).join(', ')
  return person.episodes.length ? `${rolesOf(person)} · ${latest}` : 'On no episode yet'
}

defineExpose({ open })
</script>

<template>
  <button type="button" class="button search-button" aria-haspopup="dialog" aria-keyshortcuts="Meta+K Control+K /" @click="open()">
    <FontAwesomeIcon :icon="faMagnifyingGlass"/><span class="label">Search</span><kbd>{{ mac ? '⌘K' : 'Ctrl K' }}</kbd>
  </button>

  <dialog ref="dialog" class="palette" aria-label="Search" @click="onDialogClick">
    <div class="panel">
      <div class="field">
        <FontAwesomeIcon :icon="faMagnifyingGlass"/>
        <input ref="input" v-model="text" type="search" autocomplete="off" spellcheck="false"
               placeholder="Search episodes, people and sessions" aria-label="Search" role="combobox"
               aria-autocomplete="list" aria-controls="search-items" :aria-expanded="items.length > 0"
               :aria-activedescendant="items.length ? `search-item-${selected}` : undefined" @keydown="onKeydown">
        <!-- Esc on a keyboard, Cancel on a phone -->
        <button type="button" class="close" aria-label="Close search" @click="close()">
          <kbd aria-hidden="true">esc</kbd><span class="cancel" aria-hidden="true">Cancel</span>
        </button>
      </div>

      <div class="results">
        <div v-if="!text.trim()" class="hints">
          <p>Type a code, a name or a topic. Case and diacritics don't matter, and the start of a word is enough.</p>
          <p><kbd>S05E08</kbd>, <kbd>s5e8</kbd> or <kbd>5x08</kbd> goes to an episode; <kbd>S04</kbd> lists a season.</p>
        </div>
        <p v-else-if="error" class="note">Search didn't work: {{ error }}</p>
        <p v-else-if="found && !items.length" class="note">Nothing found for “{{ found.text }}”.</p>

        <ul v-else-if="items.length" id="search-items" role="listbox" aria-label="Results">
          <template v-for="group in groups" :key="group.title">
            <li class="group" role="presentation">
              {{ group.title }}<span v-if="group.more" class="more">{{ group.items.length }} of {{ group.more }}</span>
            </li>
            <li v-for="item in group.items" :id="`search-item-${indexOf(item)}`" :key="item.key" role="option"
                :aria-selected="indexOf(item) === selected" class="item"
                :class="{ selected: indexOf(item) === selected, exact: item.kind === 'episode' && item.exact }"
                @mousemove="selected = indexOf(item)" @click="onItemClick($event, item.path)"
                @auxclick="$event.button === 1 && onItemClick($event, item.path)">
              <template v-if="item.kind === 'episode'">
                <span class="lead code">{{ item.hit.episode.code ?? '—' }}</span>
                <span class="main">
                  <span class="title" lang="sl"><MarkedText :text="item.hit.episode.title" :words="words"/><ShowBadge v-if="item.hit.episode.show" :name="item.hit.episode.show" :color="item.hit.episode.showColor"/></span>
                  <span v-if="episodeLine(item.hit).text" class="line">
                    <span v-if="episodeLine(item.hit).label" class="label">{{ `${episodeLine(item.hit).label} ` }}</span><MarkedText :text="episodeLine(item.hit).text ?? ''" :words="words"/>
                  </span>
                </span>
                <span class="side">
                  <time v-if="item.hit.episode.status === 'PUBLISHED' && item.hit.episode.airedDate" :datetime="item.hit.episode.airedDate">{{ formatDate(item.hit.episode.airedDate) }}</time>
                  <StatusLabel v-else :status="item.hit.episode.status"/>
                </span>
              </template>

              <template v-else-if="item.kind === 'person'">
                <span class="lead"><FontAwesomeIcon :icon="faUser"/></span>
                <span class="main">
                  <span class="title"><MarkedText :text="item.hit.person.name" :words="words"/></span>
                  <span class="line"><MarkedText :text="personLine(item.hit)" :words="item.hit.matchedIn[0] === 'BIO' ? words : []"/></span>
                </span>
                <span class="side"/>
              </template>

              <template v-else>
                <span class="lead"><time :datetime="item.hit.session.date">{{ shortDate(item.hit.session.date) }}</time></span>
                <span class="main">
                  <span class="title"><MarkedText :text="sessionName(item.hit.session) ?? formatDate(item.hit.session.date)" :words="words"/></span>
                  <span class="line">
                    {{ formatDate(item.hit.session.date) }}<template v-if="sessionPlace(item.hit.session)"> · <MarkedText :text="sessionPlace(item.hit.session)!" :words="words"/></template><template v-if="item.hit.session.episodes.length"> · {{ item.hit.session.episodes.map((episode) => episode.code ?? episode.title).join(', ') }}</template>
                  </span>
                </span>
                <span class="side"/>
              </template>
              <span class="enter" aria-hidden="true">↵</span>
            </li>
          </template>
        </ul>
      </div>

      <div class="foot">
        <span class="keys"><kbd>↑</kbd> <kbd>↓</kbd> to move</span>
        <span class="keys"><kbd>↵</kbd> to open</span>
        <button v-if="found && total > items.length" type="button" class="all" @click="seeAll()">See all {{ total }} results</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped lang="scss">

kbd {
  display: inline-block;
  min-width: 20px;
  padding: 0 5px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-xs);
  background: var(--hover);
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  line-height: 18px;
  color: var(--muted);
  text-align: center;
}

.search-button {
  color: var(--secondary);

  svg {
    color: var(--muted);
  }
}

.palette {
  width: min(640px, calc(100vw - 32px));
  max-height: min(560px, calc(100dvh - 120px));
  margin: 72px auto auto;
  padding: 0;
  border: 0;
  border-radius: var(--radius-lg);
  background: #FFFFFF;
  color: var(--ink);
  box-shadow: var(--shadow-dialog);
  overflow: hidden;

  &::backdrop {
    background: rgba(20, 20, 20, .38);
  }
}

.panel {
  display: flex;
  flex-direction: column;
  max-height: inherit;
}

.field {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 56px;
  padding: 0 12px 0 18px;
  border-bottom: 1px solid var(--hairline);
  flex: none;

  > svg {
    color: var(--muted);
  }

  input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: 0;
    background: none;
    font: inherit;
    font-size: var(--text-lead);
    line-height: 24px;
    color: var(--ink);

    &::placeholder {
      color: var(--muted);
    }

    // The field's own clear button would sit beside esc
    &::-webkit-search-cancel-button {
      display: none;
    }
  }

  .close {
    display: flex;
    align-items: center;
    padding: 6px;
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    cursor: pointer;

    .cancel {
      display: none;
      font-size: var(--text-lead);
      line-height: 20px;
      font-weight: 500;
      color: var(--accent-text);
    }
  }
}

.results {
  min-height: 0;
  overflow: auto;
  padding: 6px 0 8px;

  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
}

.hints, .note {
  padding: 14px 18px 10px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

.hints p + p {
  margin-top: 6px;
}

.group {
  display: flex;
  gap: 6px;
  padding: 10px 18px 4px;
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);

  .more {
    font-weight: 400;
  }
}

// An episode's code, a person or a session's date; what it is; then its status or when it aired
.item {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr) auto 16px;
  column-gap: 12px;
  align-items: center;
  margin: 0 6px;
  padding: 7px 12px;
  border-radius: var(--radius);
  cursor: pointer;

  &.selected {
    background: var(--hover);
  }

  &.exact {
    background: var(--accent-wash);

    &.selected {
      box-shadow: inset 0 0 0 1.5px var(--accent);
    }

    .code {
      color: var(--accent-text);
    }
  }
}

.lead {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;

  &.code {
    font-family: var(--font-mono);
  }
}

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.title, .line {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.title {
  font-weight: 500;

  .show {
    margin-left: 8px;
    vertical-align: 1px;
  }
}

.line {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--secondary);

  .label {
    color: var(--muted);
  }
}

.side {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;
}

.enter {
  visibility: hidden;
  font-size: var(--text-small);
  color: var(--muted);
  text-align: right;
}

.selected .enter {
  visibility: visible;
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
  padding: 9px 18px;
  border-top: 1px solid var(--hairline);
  font-size: var(--text-caption);
  line-height: 20px;
  color: var(--muted);
  background: var(--surface);

  .all {
    margin-left: auto;
    padding: 0;
    border: 0;
    background: none;
    font-size: var(--text-small);
    font-weight: 500;
    color: var(--accent-text);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }
}

// Phones: only the button's glyph in the header, and the palette takes the whole screen
@media (max-width: 640px) {
  .search-button {
    padding: 0 12px;

    .label, kbd {
      display: none;
    }
  }

  .palette {
    width: 100vw;
    max-width: none;
    height: 100dvh;
    max-height: none;
    margin: 0;
    border-radius: 0;
  }

  .panel {
    height: 100%;
  }

  .results {
    flex: 1;
  }

  .item {
    grid-template-columns: 52px minmax(0, 1fr);
  }

  .side, .enter, .keys {
    display: none;
  }

  // A word that means something to a finger, and big enough for one
  .field .close {
    min-height: 44px;
    padding: 0 6px;

    kbd {
      display: none;
    }

    .cancel {
      display: inline;
    }
  }
}

</style>
