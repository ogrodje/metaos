<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CommentCard from '@/components/CommentCard.vue'
import CommentEditor from '@/components/CommentEditor.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import { type Comment, commentAnchor, commentOn, linkedComment, nothingWritten, withWritten, type Written } from '@/comments'
import { errorMessage, graphql } from '@/graphql'
import { type Image, IMAGE_FIELDS } from '@/images'
import { useAuthStore } from '@/stores/auth'

// An episode's or a recording session's discussion, as on GitHub: its comments, oldest first, and a box at the end to
// add one. The page loads its data again after each write here (changed).
const props = defineProps<{
  // Oldest first, as the API has them
  comments: Comment[]
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

const shown = computed(() => withWritten(props.comments, written.value))

// The comment a link leads to, e.g. one opened from elsewhere, whose page loads its comments a moment later, or a
// comment's own link clicked here. Once for each link, so the page doesn't jump back to it as MetaOS's data changes.
const route = useRoute()
let scrolledTo: string | undefined
watch([() => route.hash, shown], async ([hash, comments]) => {
  const comment = hash === scrolledTo ? undefined : linkedComment(hash, comments)
  if (!comment) return
  scrolledTo = hash
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
  // Its buttons went with it: the discussion has the focus instead, where it is
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
  <section aria-labelledby="discussion">
    <div class="heading">
      <h2 id="discussion" ref="heading" tabindex="-1">Discussion</h2>
      <span class="count">{{ shown.length }}</span>
    </div>

    <p v-if="!shown.length" class="note">No comments yet.</p>
    <ol v-else class="thread">
      <CommentCard v-for="comment in shown" :key="comment.id" :comment="comment" @edited="onEdited" @deleted="onDeleted"/>
    </ol>

    <div class="composer">
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

// Focused only when a comment's buttons are gone, as it's deleted
h2:focus {
  outline: 0;
}

// The comments on a rail through their pictures, as on GitHub
.thread {
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

// In line with the comments, with your picture beside it
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
  font-size: 13px;
  line-height: 20px;
  color: var(--accent-text);
}

@media (max-width: 640px) {
  .composer {
    column-gap: 10px;
  }
}

</style>
