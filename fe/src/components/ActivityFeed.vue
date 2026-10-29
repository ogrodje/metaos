<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { activity, type ActivityFilter, type ChangeEntry, filtered } from '@/activity'
import ChipGroup from '@/components/ChipGroup.vue'
import CommentCard from '@/components/CommentCard.vue'
import CommentEditor from '@/components/CommentEditor.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import { type Comment, commentAnchor, commentOn, linkedComment, nothingWritten, withWritten, type Written } from '@/comments'
import { type Change, DATE_LOCALE, TIME_ZONE } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { type Image, IMAGE_FIELDS } from '@/images'
import { useAuthStore } from '@/stores/auth'

// An episode's or a recording session's activity, as on GitHub: its comments and its history in one feed by day,
// oldest first, with one person's changes made close together as one entry (activity.ts), and a box at the end to
// comment. Everything, or only the comments or the changes. The page loads its data again after each write (changed).
const props = defineProps<{
  // Oldest first, as the API has them
  comments: Comment[]
  changes: Change[]
  // What it's on: one of the two
  episodeId?: string
  sessionId?: string
}>()

const emit = defineEmits<{
  changed: []
}>()

const auth = useAuthStore()

// What was written here, as MetaOS answered, shown until the page's comments change: its reload a moment later brings
// them, and anything newer would too
const written = ref<Written>(nothingWritten())
watch(() => props.comments, () => {
  written.value = nothingWritten()
})

const comments = computed(() => withWritten(props.comments, written.value))
const entries = computed(() => activity(props.changes, comments.value))

const filter = ref<ActivityFilter>('all')
const filters = computed(() => [
  { value: 'all' as ActivityFilter, title: 'All', count: entries.value.length },
  { value: 'comments' as ActivityFilter, title: 'Comments', count: comments.value.length },
  { value: 'changes' as ActivityFilter, title: 'Changes', count: entries.value.length - comments.value.length },
])

const day = (at: string) => new Date(at).toLocaleDateString(DATE_LOCALE, { dateStyle: 'medium', timeZone: TIME_ZONE })
const time = (at: string) => new Date(at).toLocaleTimeString(DATE_LOCALE, { hour: '2-digit', minute: '2-digit', timeZone: TIME_ZONE })

// The entries shown, each day's date before its first
const shown = computed(() => {
  let previous: string | undefined
  return filtered(entries.value, filter.value).map((entry) => {
    const date = day(entry.at)
    const item = { entry, day: date !== previous ? date : null }
    previous = date
    return item
  })
})

// The oldest entry, where it all began, marked on the rail
const first = computed(() => entries.value.find((entry) => entry.type === 'changes')?.key)

function when(entry: ChangeEntry) {
  const from = time(entry.at)
  const until = time(entry.until)
  return from === until ? from : `${from}–${until}`
}

// The comment a link leads to, e.g. one opened from elsewhere, whose page loads its comments a moment later, or a
// comment's own link clicked here; with everything shown, so it's there. Once for each link, so the page doesn't jump
// back to it as MetaOS's data changes.
const route = useRoute()
let scrolledTo: string | undefined
watch([() => route.hash, comments], async ([hash, all]) => {
  const comment = hash === scrolledTo ? undefined : linkedComment(hash, all)
  if (!comment) return
  scrolledTo = hash
  if (filter.value === 'changes') filter.value = 'all'
  await nextTick()
  document.getElementById(commentAnchor(comment))?.scrollIntoView({ block: 'start' })
}, { immediate: true })

// Kept here, so it stays while the page shows MetaOS's changes
const draft = ref('')
const posting = ref(false)
const error = ref<string>()

const heading = ref<HTMLHeadingElement>()

async function post() {
  posting.value = true
  error.value = undefined
  try {
    const { query, variables } = commentOn(props, draft.value)
    const { comment } = await graphql<{ comment: Comment }>(query, variables)
    written.value = { ...written.value, added: [...written.value.added, comment] }
    draft.value = ''
    // A comment written while only the changes are shown would seem to vanish
    if (filter.value === 'changes') filter.value = 'all'
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
  // Its buttons went with it: the feed has the focus instead, where it is
  heading.value?.focus({ preventScroll: true })
}

// You, beside the box, as your comments will show you: as your person, once one's linked to you by email, or else by
// your username
interface Me {
  username: string | null
  person: { name: string, avatar: Image | null } | null
}

const me = ref<{ name: string, avatar: Image | null }>()

onMounted(async () => {
  try {
    const data = await graphql<{ me: Me | null }>(`{ me { username person { name avatar { ${IMAGE_FIELDS} } } } }`)
    const name = data.me?.person?.name ?? data.me?.username ?? auth.username
    if (name) me.value = { name, avatar: data.me?.person?.avatar ?? null }
  } catch (e) {
    console.error("Who's signed in didn't load", e)
  }
})
</script>

<template>
  <section aria-labelledby="activity">
    <div class="heading">
      <h2 id="activity" ref="heading" tabindex="-1">Activity</h2>
      <ChipGroup v-model="filter" class="filters" label="Show" :options="filters"/>
    </div>

    <p v-if="!shown.length" class="note">{{ filter === 'changes' ? 'No changes yet.' : 'No comments yet.' }}</p>
    <ol v-else class="feed">
      <template v-for="{ entry, day: date } in shown" :key="entry.key">
        <li v-if="date" class="day"><time :datetime="entry.at.slice(0, 10)">{{ date }}</time></li>
        <CommentCard v-if="entry.type === 'comment'" :comment="entry.comment" @edited="onEdited" @deleted="onDeleted"/>
        <li v-else class="change">
          <span class="pin" aria-hidden="true"><span class="dot" :class="{ first: entry.key === first }"/></span>
          <div class="what">
            <p class="headline">{{ entry.headline }}</p>
            <dl v-if="entry.lines.length" class="lines">
              <div v-for="(line, i) in entry.lines" :key="i" :class="{ solo: !line.label }">
                <dt v-if="line.label">{{ line.label }}</dt>
                <dd>{{ line.text }}</dd>
              </div>
            </dl>
            <!-- Who and how, as the history said it: outside MetaOS when neither, e.g. a migration -->
            <p class="meta">
              <template v-if="entry.actor || entry.how">{{ [entry.actor, entry.how].filter(Boolean).join(' · ') }}</template>
              <template v-else>Outside MetaOS</template>
              · <time :datetime="entry.at">{{ when(entry) }}</time>
              <template v-if="entry.count > 1"> · {{ entry.count }} changes</template>
            </p>
          </div>
        </li>
      </template>
    </ol>

    <div v-if="filter !== 'changes'" class="composer">
      <PersonAvatar v-if="me" :image="me.avatar" :name="me.name" :size="32"/>
      <CommentEditor v-model="draft" label="Comment" placeholder="Leave a comment" submit-label="Comment"
                     busy-label="Commenting…" :busy="posting" @submit="post">
        <p v-if="error" class="error" role="alert">The comment wasn't posted: {{ error }}</p>
      </CommentEditor>
    </div>
  </section>
</template>

<style scoped lang="scss">
@use '../assets/heading';

.heading {
  flex-wrap: wrap;
}

// Focused only when a comment's buttons are gone, as it's deleted
h2:focus {
  outline: 0;
}

// At the heading's end; on phones under it, as ChipGroup has it
@media (min-width: 641px) {
  .filters {
    margin-left: auto;
  }
}

// Comments and changes on a rail through their pictures and dots, as on GitHub
.feed {
  position: relative;
  display: grid;
  gap: 16px;
  padding: 0;
  list-style: none;

  &::before {
    content: '';
    position: absolute;
    left: 15px;
    top: 8px;
    bottom: 8px;
    width: 2px;
    background: var(--rule);
  }
}

// Each day's date, on the rail's right
.day {
  position: relative;
  padding: 8px 0 0 44px;
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);

  &:first-child {
    padding-top: 0;
  }
}

.change {
  position: relative;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  column-gap: 12px;
  align-items: start;
}

.pin {
  display: grid;
  place-items: center;
  height: 22px;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #FFFFFF;
  border: 1px solid var(--hairline-strong);

  // Where it all began
  &.first {
    background: var(--accent);
    border-color: var(--accent);
  }
}

.what {
  min-width: 0;
}

.headline {
  font-size: var(--text-body);
  line-height: 22px;
  font-weight: 500;
}

// Each field with what it became, or a change in words
.lines {
  display: grid;
  gap: 2px;
  margin-top: 2px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);

  div {
    display: grid;
    grid-template-columns: 112px minmax(0, 1fr);
    column-gap: 8px;
  }

  .solo {
    grid-template-columns: minmax(0, 1fr);
  }

  dt {
    color: var(--muted);
  }

  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
}

.meta {
  margin-top: 4px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

// In line with the feed, with your picture beside it
.composer {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  column-gap: 12px;
  align-items: start;
  margin-top: 16px;

  .editor {
    grid-column: 2;
  }
}

.note + .composer {
  margin-top: 12px;
}

.error {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

@media (max-width: 640px) {
  .lines div {
    grid-template-columns: minmax(0, 1fr);
  }

  .composer {
    column-gap: 10px;
  }
}
</style>
