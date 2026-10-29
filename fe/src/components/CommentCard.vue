<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import CommentEditor from '@/components/CommentEditor.vue'
import MarkdownText from '@/components/MarkdownText.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import { type Comment, commentAnchor, commentText, DELETE_COMMENT, EDIT_COMMENT, fullDateTime, toolLabel } from '@/comments'
import { formatDate } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { personPath } from '@/people'

// A comment in a discussion: who wrote it, when and how, and what. Its author edits it in place, and deletes it once
// they've said so here, rather than in a dialog.
const props = defineProps<{
  comment: Comment
  // With Fold, back to a line, e.g. in an episode's side panel (ActivityPanel)
  foldable?: boolean
}>()

const emit = defineEmits<{
  // As MetaOS answered, which the discussion shows until the page has it too
  edited: [comment: Comment]
  deleted: [id: string]
  fold: []
}>()

const id = useId()
const editing = ref(false)
// Kept here while it's edited, so it stays while the page shows MetaOS's changes
const draft = ref('')
const confirming = ref(false)
const busy = ref(false)
const error = ref<string>()

const editButton = ref<HTMLButtonElement>()
const deleteButton = ref<HTMLButtonElement>()
const keepButton = ref<HTMLButtonElement>()

const via = computed(() => toolLabel(props.comment))

// Its link, as on GitHub "commented ..." leads to it: the page's address with its anchor, to open or pass on. The router
// changes the address without the browser's :target, so the route says which one a link led to.
const route = useRoute()
const anchor = computed(() => commentAnchor(props.comment))
const linked = computed(() => route.hash === `#${anchor.value}`)

function focus(button: typeof editButton) {
  void nextTick(() => button.value?.focus())
}

function edit() {
  draft.value = props.comment.body
  error.value = undefined
  editing.value = true
}

function stopEditing() {
  editing.value = false
  error.value = undefined
  focus(editButton)
}

async function save() {
  // The same text again changes nothing
  if (commentText(draft.value) === props.comment.body) return stopEditing()
  busy.value = true
  error.value = undefined
  try {
    const { comment } = await graphql<{ comment: Comment }>(EDIT_COMMENT, { id: props.comment.id, body: draft.value })
    emit('edited', comment)
    stopEditing()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

// Cancel first, so a second press doesn't delete it
function askToDelete() {
  error.value = undefined
  confirming.value = true
  focus(keepButton)
}

function keep() {
  confirming.value = false
  focus(deleteButton)
}

async function remove() {
  if (busy.value) return
  busy.value = true
  error.value = undefined
  try {
    await graphql(DELETE_COMMENT, { id: props.comment.id })
    emit('deleted', props.comment.id)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <li :id="anchor" class="comment" :class="{ linked }">
    <PersonAvatar :image="comment.author.person?.avatar ?? null" :name="comment.author.name" :size="32"/>

    <div class="main">
      <!-- Edited in its place, as on GitHub -->
      <CommentEditor v-if="editing" v-model="draft" label="Edit your comment" submit-label="Save" busy-label="Saving…"
                     :busy="busy" cancellable autofocus @submit="save" @cancel="stopEditing">
        <p v-if="error" class="error" role="alert">The comment wasn't saved: {{ error }}</p>
      </CommentEditor>

      <article v-else class="card">
        <header class="head">
          <RouterLink v-if="comment.author.person" class="author" :to="personPath(comment.author.person)">{{ comment.author.name }}</RouterLink>
          <b v-else class="author">{{ comment.author.name }}</b>
          <RouterLink class="permalink" :to="{ hash: `#${anchor}` }" :aria-current="linked ? 'location' : undefined">commented <time :datetime="comment.createdAt" :title="fullDateTime(comment.createdAt)">{{ formatDate(comment.createdAt) }}</time></RouterLink>
          <span v-if="via" class="via" :title="`Written through MCP, with the ${comment.tool} tool`">{{ via }}</span>
          <time v-if="comment.editedAt" class="edited" :datetime="comment.editedAt" :title="`Edited ${fullDateTime(comment.editedAt)}`">edited</time>
          <span v-if="(comment.mine || foldable) && !confirming" class="actions">
            <template v-if="comment.mine">
              <button ref="editButton" type="button" @click="edit">Edit</button>
              <button ref="deleteButton" type="button" @click="askToDelete">Delete</button>
            </template>
            <button v-if="foldable" type="button" @click="emit('fold')">Fold</button>
          </span>
        </header>

        <MarkdownText class="body" :source="comment.body"/>

        <div v-if="confirming" class="confirm" role="group" :aria-labelledby="`${id}-confirm`" @keydown.esc="keep">
          <span :id="`${id}-confirm`" class="question">Delete this comment?</span>
          <span class="buttons">
            <button type="button" class="button delete" :aria-disabled="busy || undefined" @click="remove">
              {{ busy ? 'Deleting…' : 'Delete' }}
            </button>
            <button ref="keepButton" type="button" class="button" :disabled="busy" @click="keep">Cancel</button>
          </span>
        </div>
      </article>

      <p v-if="error && !editing" class="error" role="alert">The comment wasn't deleted: {{ error }}</p>
    </div>
  </li>
</template>

<style scoped lang="scss">

// Its picture on the discussion's rail, beside the card. Positioned, so it's in front of the rail.
.comment {
  position: relative;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  column-gap: 12px;
  align-items: start;
  scroll-margin-top: 24px;
}

// From a link to it
.comment.linked .card {
  border-color: var(--accent);
}

// Its link, quiet as the head's other words until it's pointed at
.permalink:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.main {
  min-width: 0;
}

.card {
  overflow: hidden;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  padding: 6px 8px 6px 14px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  background: var(--surface);
  border-bottom: 1px solid var(--hairline);
}

.author {
  font-weight: 600;
  color: var(--ink);
}

// How it was written, when not on MetaOS's pages
.via {
  padding: 0 7px;
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  line-height: 18px;
  color: var(--secondary);
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

// Its title says when
.edited {
  text-decoration: underline dotted;
  text-underline-offset: 3px;
  cursor: default;
}

// Quiet, at the head's end; as tall as icon links, without making the head taller
.actions {
  display: flex;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) auto;

  button {
    min-height: var(--icon-link);
    padding: 0 8px;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--muted);
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    cursor: pointer;

    &:hover {
      color: var(--ink);
      background: var(--hover);
    }
  }
}

.card > .body {
  padding: 12px 14px;
  overflow-wrap: anywhere;

  // Its own lists, not nested in the discussion's, which browsers would give hollow bullets
  > :deep(ul) {
    list-style-type: disc;
  }
}

// The question, and its buttons together at the end, under it where they don't fit beside it
.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px 16px;
  padding: 8px 14px;
  background: var(--surface);
  border-top: 1px solid var(--rule);

  .question {
    flex: 1 1 auto;
    font-weight: 500;
  }

  .buttons {
    display: flex;
    gap: 8px;
  }
}

.button.delete {
  color: var(--accent-text);

  &:hover {
    color: var(--accent-text);
    border-color: var(--accent);
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    border-color: var(--hairline);
    cursor: default;
  }
}

.button:disabled {
  color: var(--muted);
  cursor: default;
}

.error {
  margin-top: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

// Beside the buttons when editing, which have their own space
.main > .editor .error {
  margin-top: 0;
}

@media (max-width: 640px) {
  .comment {
    column-gap: 10px;
  }
}

</style>
