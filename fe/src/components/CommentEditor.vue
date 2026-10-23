<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId, watch } from 'vue'
import MarkdownText from '@/components/MarkdownText.vue'
import { commentText, editorShortcut, lengthNote, postable } from '@/comments'

// A comment box, as on GitHub: Write, and Preview, which shows the text as the discussion will (MarkdownText). For a
// new comment and for editing one in place. ⌘↵ or Ctrl+↵ sends it from either; Ctrl+Shift+P (⌘⇧P) switches them.
const props = defineProps<{
  // What it's for, for screen readers, e.g. Comment
  label: string
  submitLabel: string
  // While what was sent is on its way: the text waits, and the button says so
  busy?: boolean
  busyLabel?: string
  placeholder?: string
  // With a Cancel button, e.g. when editing
  cancellable?: boolean
  // Focused once it's shown, at the end of its text
  autofocus?: boolean
}>()

// The text, which the parent keeps, so it stays while the page shows MetaOS's changes
const text = defineModel<string>({ required: true })

const emit = defineEmits<{
  submit: []
  cancel: []
}>()

type Tab = 'write' | 'preview'

const id = useId()
const tab = ref<Tab>('write')
const textarea = ref<HTMLTextAreaElement>()
const writeTab = ref<HTMLButtonElement>()
const previewTab = ref<HTMLButtonElement>()
// As tall as the text was, so the box doesn't jump when it's previewed
const previewHeight = ref<number>()

const mac = /Mac|iPhone|iPad/.test(navigator.userAgent)

// What will be posted, as MetaOS keeps it
const preview = computed(() => commentText(text.value))
const length = computed(() => lengthNote(text.value))
const ready = computed(() => !props.busy && postable(text.value))

function show(to: Tab, focus?: 'tab' | 'text') {
  if (to === 'preview' && tab.value === 'write' && textarea.value) previewHeight.value = textarea.value.offsetHeight
  tab.value = to
  if (!focus) return
  void nextTick(() => {
    if (focus === 'text' && to === 'write') textarea.value?.focus()
    else (to === 'write' ? writeTab : previewTab).value?.focus()
  })
}

function submit() {
  if (ready.value) emit('submit')
}

function onKeydown(event: KeyboardEvent) {
  const shortcut = editorShortcut(event)
  if (!shortcut) return
  event.preventDefault()
  if (shortcut === 'submit') submit()
  else if (tab.value === 'write') show('preview', 'tab')
  else show('write', 'text')
}

// Between the tabs with the arrow keys, Home and End, as tabs go
function onTabKeydown(event: KeyboardEvent) {
  const to: Tab | undefined = event.key === 'Home' ? 'write'
    : event.key === 'End' ? 'preview'
      : event.key === 'ArrowLeft' || event.key === 'ArrowRight' ? (tab.value === 'write' ? 'preview' : 'write')
        : undefined
  if (!to) return
  event.preventDefault()
  show(to, 'tab')
}

// Emptied while it's previewed: it was sent, and the next is written
watch(text, (value) => {
  if (!value && tab.value === 'preview') show('write')
})

onMounted(() => {
  if (!props.autofocus || !textarea.value) return
  textarea.value.focus()
  textarea.value.setSelectionRange(text.value.length, text.value.length)
})
</script>

<template>
  <form class="editor" :aria-busy="busy || undefined" @submit.prevent="submit" @keydown="onKeydown">
    <div class="box">
      <div class="tabs" role="tablist" :aria-label="label" @keydown="onTabKeydown">
        <button :id="`${id}-write`" ref="writeTab" type="button" role="tab" :aria-selected="tab === 'write'"
                :aria-controls="`${id}-write-panel`" :tabindex="tab === 'write' ? 0 : -1"
                aria-keyshortcuts="Control+Shift+P Meta+Shift+P" @click="show('write', 'text')">Write</button>
        <button :id="`${id}-preview`" ref="previewTab" type="button" role="tab" :aria-selected="tab === 'preview'"
                :aria-controls="`${id}-preview-panel`" :tabindex="tab === 'preview' ? 0 : -1"
                aria-keyshortcuts="Control+Shift+P Meta+Shift+P" @click="show('preview')">Preview</button>
      </div>

      <!-- Hidden rather than gone while it's previewed, so the text keeps its undo history -->
      <div v-show="tab === 'write'" :id="`${id}-write-panel`" role="tabpanel" :aria-labelledby="`${id}-write`">
        <textarea ref="textarea" v-model="text" :aria-label="label" :placeholder="placeholder" :readonly="busy"
                  :aria-invalid="length?.over || undefined" :aria-describedby="length ? `${id}-length` : undefined"/>
      </div>
      <div v-show="tab === 'preview'" :id="`${id}-preview-panel`" class="preview" role="tabpanel"
           :aria-labelledby="`${id}-preview`" tabindex="0" :style="{ minHeight: previewHeight ? `${previewHeight}px` : undefined }">
        <template v-if="tab === 'preview'">
          <MarkdownText v-if="preview" :source="preview"/>
          <p v-else class="nothing">Nothing to preview</p>
        </template>
      </div>

      <div class="foot">
        <span>Markdown is supported</span>
        <span class="aside">
          <span v-if="length" :id="`${id}-length`" :class="{ over: length.over }">{{ length.text }}</span>
          <span class="keys">{{ mac ? '⌘↵' : 'Ctrl+↵' }} to {{ submitLabel.toLowerCase() }}</span>
        </span>
      </div>
    </div>

    <div class="actions">
      <!-- What went wrong, beside the buttons -->
      <div class="message"><slot/></div>
      <button v-if="cancellable" type="button" class="button" :disabled="busy" @click="emit('cancel')">Cancel</button>
      <!-- Not disabled, which would lose the focus while it's sent, but said to be -->
      <button type="submit" class="button primary" :aria-disabled="!ready" aria-keyshortcuts="Meta+Enter Control+Enter">
        {{ busy ? busyLabel ?? submitLabel : submitLabel }}
      </button>
    </div>
  </form>
</template>

<style scoped lang="scss">

.box {
  overflow: hidden;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: 8px;

  &:focus-within {
    border-color: var(--muted);
  }
}

// Folder tabs on a grey strip: the one shown is joined to the text under it
.tabs {
  display: flex;
  gap: 2px;
  padding: 6px 6px 0;
  background: #FAFAFA;
  border-bottom: 1px solid var(--hairline);

  button {
    min-height: var(--icon-link);
    margin-bottom: -1px;
    padding: 5px 12px 6px;
    font-size: 13px;
    line-height: 20px;
    font-weight: 500;
    color: var(--muted);
    background: none;
    border: 1px solid transparent;
    border-bottom: 0;
    border-radius: 6px 6px 0 0;
    cursor: pointer;

    &:hover {
      color: var(--ink);
    }

    &[aria-selected='true'] {
      color: var(--ink);
      background: #FFFFFF;
      border-color: var(--hairline);
    }
  }
}

textarea {
  display: block;
  width: 100%;
  min-height: 96px;
  max-height: 60vh;
  // As tall as its text, where browsers can
  field-sizing: content;
  padding: 12px 14px;
  font: inherit;
  font-size: 14px;
  line-height: 22px;
  color: var(--ink);
  background: #FFFFFF;
  border: 0;
  outline: 0;
  resize: vertical;

  &::placeholder {
    color: #9A9A9A;
  }
}

.preview {
  min-height: 96px;
  padding: 12px 14px;

  &:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
}

.nothing {
  color: var(--muted);
}

.foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 2px 16px;
  padding: 6px 14px 8px;
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
  border-top: 1px solid var(--rule);

  .aside {
    display: flex;
    flex-wrap: wrap;
    gap: 2px 16px;
  }

  .over {
    font-weight: 500;
    color: var(--accent-text);
  }
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;

  .message {
    flex: 1 1 240px;
    min-width: 0;
  }

  .message:empty {
    display: none;
  }
}

// The box's one action, filled; said to be unavailable while there's nothing to send, or it's being sent
.button.primary {
  color: #FFFFFF;
  background: var(--ink);
  border-color: var(--ink);

  &:hover {
    color: #FFFFFF;
    background: #2B2B2B;
    border-color: #2B2B2B;
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    background: var(--hover);
    border-color: var(--hairline);
    cursor: default;
  }
}

.button:disabled {
  color: var(--muted);
  cursor: default;
}

button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

// Without a keyboard, there are no shortcuts to tell of
@media (hover: none) {
  .keys {
    display: none;
  }
}

// Phones zoom into text under 16 px when it's focused
@media (max-width: 640px) {
  textarea {
    font-size: 16px;
    line-height: 24px;
  }
}

</style>
