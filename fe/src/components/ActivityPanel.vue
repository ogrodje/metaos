<script setup lang="ts">
import { computed, inject, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { activity, type ActivityFilter, filtered, isNew, shortWhen } from '@/activity'
import ChipGroup from '@/components/ChipGroup.vue'
import CommentCard from '@/components/CommentCard.vue'
import CommentEditor from '@/components/CommentEditor.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import {
  type Comment, commentAnchor, commentOn, fullDateTime, linkedComment, nothingWritten, toolLabel, withWritten, type Written,
} from '@/comments'
import type { Change } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { SIDE_PANEL } from '@/scroll'
import { plainText } from '@/seo'

// An episode's activity in its side panel, where it stays in view however far down the page is read: a box to comment,
// then its latest comments and changes, the newest first, with what came since the viewer last opened the page marked
// new; all of them once asked for. Each is a line or so: a comment's first lines, which open it whole, with Edit and
// Delete on one's own; a change's headline, which opens what it changed, field by field. A link to a comment opens it.
// Told as a session's ActivityFeed tells them (activity.ts). The page loads its data again after each write (changed).
const props = defineProps<{
  // Oldest first, as the API has them
  comments: Comment[]
  changes: Change[]
  episodeId: string
}>()

const emit = defineEmits<{
  changed: []
}>()

// How many it shows until all are asked for
const LATEST = 5

// What was written here, as MetaOS answered, shown until the page's comments change (ActivityFeed)
const written = ref<Written>(nothingWritten())
watch(() => props.comments, () => {
  written.value = nothingWritten()
})

const comments = computed(() => withWritten(props.comments, written.value))
const entries = computed(() => activity(props.changes, comments.value).reverse())

const all = ref(false)
const filter = ref<ActivityFilter>('all')
const filters = computed(() => [
  { value: 'all' as ActivityFilter, title: 'All', count: entries.value.length },
  { value: 'comments' as ActivityFilter, title: 'Comments', count: comments.value.length },
  { value: 'changes' as ActivityFilter, title: 'Changes', count: entries.value.length - comments.value.length },
])
const shown = computed(() => (all.value ? filtered(entries.value, filter.value) : entries.value.slice(0, LATEST)))

// The entries opened: a comment whole, a change's fields; each folded to a line or so otherwise, so the panel stays short
const opened = ref(new Set<string>())

function toggle(key: string) {
  const next = new Set(opened.value)
  if (!next.delete(key)) next.add(key)
  opened.value = next
}

// When the viewer last opened the episode's page, in this browser, and who they are: what others did since is new to
// them. Read once it's shown and kept for the visit, so what comes while it's open is new too; and this visit's time
// kept for the next.
const seen = ref<number | null>(null)
const me = ref<string | null>(null)

onMounted(async () => {
  const key = `metaos.seen.episode.${props.episodeId}`
  try {
    seen.value = Number(localStorage.getItem(key)) || null
    localStorage.setItem(key, String(Date.now()))
  } catch {
    // Without storage, e.g. in a private window, nothing is new
  }
  try {
    const data = await graphql<{ me: { username: string | null, person: { name: string } | null } | null }>(
      '{ me { username person { name } } }')
    me.value = data.me?.person?.name ?? data.me?.username ?? null
  } catch (e) {
    console.error("Who's signed in didn't load", e)
  }
})

const fresh = computed(() => new Set(entries.value.filter((entry) => isNew(entry, seen.value, me.value)).map((entry) => entry.key)))

// The comment a link leads to (ActivityFeed): with all of them shown, so it's there, once for each link; the page scrolls
// to it, the side panel along with it, as the panel has no scrollbar of its own (useStickySide)
const route = useRoute()
const side = inject(SIDE_PANEL, null)
let scrolledTo: string | undefined
watch([() => route.hash, comments], async ([hash, listed]) => {
  const comment = hash === scrolledTo ? undefined : linkedComment(hash, listed)
  if (!comment) return
  scrolledTo = hash
  all.value = true
  if (filter.value === 'changes') filter.value = 'all'
  opened.value = new Set([...opened.value, `comment-${comment.id}`])
  await nextTick()
  const element = document.getElementById(commentAnchor(comment))
  if (element && side) side.reveal(element)
  else element?.scrollIntoView({ block: 'start' })
}, { immediate: true })

// The box, closed to a line until it's written in; its text kept while it's closed and while the page shows changes
const writing = ref(false)
const draft = ref('')
const posting = ref(false)
const error = ref<string>()

const heading = ref<HTMLHeadingElement>()
const writeButton = ref<HTMLButtonElement>()

function stopWriting() {
  writing.value = false
  error.value = undefined
  void nextTick(() => writeButton.value?.focus())
}

async function post() {
  posting.value = true
  error.value = undefined
  try {
    const { query, variables } = commentOn({ episodeId: props.episodeId }, draft.value)
    const { comment } = await graphql<{ comment: Comment }>(query, variables)
    written.value = { ...written.value, added: [...written.value.added, comment] }
    draft.value = ''
    if (filter.value === 'changes') filter.value = 'all'
    stopWriting()
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    posting.value = false
  }
}

function onEdited(comment: Comment) {
  written.value = { ...written.value, edited: [...written.value.edited, comment] }
  emit('changed')
}

function onDeleted(id: string) {
  written.value = { ...written.value, deleted: [...written.value.deleted, id] }
  emit('changed')
  heading.value?.focus({ preventScroll: true })
}
</script>

<template>
  <section class="activity-panel" aria-labelledby="activity">
    <div class="head">
      <h2 id="activity" ref="heading" tabindex="-1">Activity</h2>
      <span v-if="entries.length" class="tag">{{ entries.length }}</span>
      <span v-if="fresh.size" class="tag new">{{ fresh.size }} new</span>
    </div>

    <CommentEditor v-if="writing" v-model="draft" label="Comment" placeholder="Leave a comment" submit-label="Comment"
                   busy-label="Commenting…" :busy="posting" cancellable autofocus @submit="post" @cancel="stopWriting">
      <p v-if="error" class="error" role="alert">The comment wasn't posted: {{ error }}</p>
    </CommentEditor>
    <button v-else ref="writeButton" type="button" class="write" @click="writing = true">
      {{ draft ? 'Go on with your comment…' : 'Write a comment…' }}
    </button>

    <ChipGroup v-if="all" v-model="filter" class="filters" label="Show" :options="filters"/>

    <p v-if="!shown.length" class="note">{{ filter === 'changes' ? 'No changes yet.' : 'Nothing yet.' }}</p>
    <ol v-else class="feed">
      <template v-for="entry in shown" :key="entry.key">
        <!-- Opened: the comment whole, with Edit and Delete on one's own, and Fold -->
        <CommentCard v-if="entry.type === 'comment' && opened.has(entry.key)" :comment="entry.comment" foldable
                     @fold="toggle(entry.key)" @edited="onEdited" @deleted="onDeleted"/>
        <!-- Who and when, and its first lines, which open it whole -->
        <li v-else-if="entry.type === 'comment'" class="comment">
          <PersonAvatar :image="entry.comment.author.person?.avatar ?? null" :name="entry.comment.author.name" :size="24"/>
          <div class="what">
            <p class="who">
              <b>{{ entry.comment.author.name }}</b>
              <time class="when" :datetime="entry.at" :title="fullDateTime(entry.at)">{{ shortWhen(entry.at) }}</time>
              <span v-if="entry.comment.tool" class="when" :title="toolLabel(entry.comment) ?? undefined"> · MCP</span>
              <span v-if="fresh.has(entry.key)" class="new">new</span>
            </p>
            <button type="button" class="said" :aria-expanded="false" @click="toggle(entry.key)">
              <span class="clamp">{{ plainText(entry.comment.body) }}</span>
            </button>
          </div>
        </li>
        <li v-else class="change" :class="{ fresh: fresh.has(entry.key) }">
          <span class="pin" aria-hidden="true"><span class="dot"/></span>
          <div class="what">
            <!-- What it changed, field by field, once asked -->
            <button v-if="entry.lines.length" type="button" class="headline opens" :aria-expanded="opened.has(entry.key)"
                    @click="toggle(entry.key)">{{ entry.headline }}</button>
            <p v-else class="headline">{{ entry.headline }}</p>
            <dl v-if="opened.has(entry.key)" class="lines">
              <div v-for="(line, i) in entry.lines" :key="i">
                <dt v-if="line.label">{{ line.label }}</dt>
                <dd>{{ line.text }}</dd>
              </div>
            </dl>
            <!-- Who and how, as the history said it: outside MetaOS when neither, e.g. a migration -->
            <p class="when">
              {{ entry.actor || entry.how ? [entry.actor, entry.how].filter(Boolean).join(' · ') : 'Outside MetaOS' }}
              · <time :datetime="entry.at" :title="fullDateTime(entry.at)">{{ shortWhen(entry.at) }}</time>
              <span v-if="fresh.has(entry.key)" class="new">new</span>
            </p>
          </div>
        </li>
      </template>
    </ol>

    <button v-if="entries.length > LATEST" type="button" class="text-button more" :aria-expanded="all" @click="all = !all">
      {{ all ? 'Only the latest' : `Show all ${entries.length}` }}
    </button>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.activity-panel {
  display: grid;
  gap: 10px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;

  h2 {
    font-size: var(--text-lead);
    line-height: 24px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }

  // Focused only when a comment's buttons are gone, as it's deleted
  h2:focus {
    outline: 0;
  }
}

.tag {
  @include heading.count-tag;

  &.new {
    color: var(--accent-text);
    background: var(--accent-wash);
  }
}

// A comment box, closed to a line: it opens as it's pressed
.write {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 40px;
  padding: 0 12px;
  font: inherit;
  color: var(--muted);
  text-align: left;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
  cursor: text;

  &:hover {
    border-color: var(--hairline-strong);
  }
}

.feed {
  padding: 0;
  list-style: none;

  > li {
    padding: 10px 0;
    border-top: 1px solid var(--rule);

    &:first-child {
      border-top: 0;
    }
  }
}

.comment, .change {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  column-gap: 10px;
  align-items: start;
}

.what {
  min-width: 0;
}

.who b {
  font-weight: 600;
}

.when {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.who .when {
  margin-left: 4px;
}

.new {
  margin-left: 6px;
  font-size: var(--text-micro);
  font-weight: 600;
  color: var(--accent-text);
}

// Its first lines, quiet in a box, so it reads apart from the changes; whole once it's pressed
.said {
  display: block;
  width: 100%;
  margin-top: 4px;
  padding: 7px 10px;
  font: inherit;
  color: var(--ink);
  text-align: left;
  background: var(--surface);
  border: 1px solid var(--rule);
  border-radius: var(--radius);
  cursor: pointer;

  &:hover {
    border-color: var(--hairline-strong);
  }
}

.clamp {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
  overflow-wrap: anywhere;
}

.pin {
  display: grid;
  place-items: center;
  height: 20px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--faint);

  .fresh & {
    background: var(--accent);
  }
}

.headline {
  color: var(--secondary);
}

// A headline that opens what it changed, a line among words until it's pointed at
button.headline {
  padding: 0;
  font: inherit;
  text-align: left;
  background: none;
  border: 0;
  cursor: pointer;

  &:hover, &[aria-expanded="true"] {
    color: var(--ink);
    text-decoration: underline;
    text-decoration-color: var(--faint);
    text-underline-offset: 3px;
  }
}

// Each field with what it became, or a change in words, under each other: the panel has no room for them side by side
.lines {
  display: grid;
  gap: 2px;
  margin: 2px 0;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--secondary);

  dt {
    color: var(--muted);
  }

  dd {
    margin: 0 0 2px;
    overflow-wrap: anywhere;
  }
}

.more {
  justify-self: start;
}

.error {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}
</style>
