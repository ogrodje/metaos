<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

// Raw HTML stays text (html: false) and links go only to safe protocols (markdown-it's own check), so no summary can
// run a script here, AI-written ones included. Single line breaks stay breaks, as the summaries are written.
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
// Bare domains (ogrodje.si) are links too, as before markdown-it 15 turned that off
markdown.linkify.set({ fuzzyLink: true })

// Links lead out of MetaOS, so they open in a tab of their own
const linkOpen = markdown.renderer.rules.link_open ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index]?.attrSet('target', '_blank')
  tokens[index]?.attrSet('rel', 'noopener noreferrer')
  return linkOpen(tokens, index, options, env, self)
}

const props = defineProps<{ source: string }>()

const html = computed(() => markdown.render(props.source))
</script>

<template>
  <div class="markdown" v-html="html"></div>
</template>

<style scoped lang="scss">

.markdown {
  max-width: 680px;
  color: var(--secondary);

  :deep(p), :deep(ul), :deep(ol), :deep(blockquote), :deep(pre) {
    margin: 0 0 12px;
  }

  > :deep(:last-child) {
    margin-bottom: 0;
  }

  // As the page's section headings, whatever their level in the text
  :deep(h1), :deep(h2), :deep(h3), :deep(h4), :deep(h5), :deep(h6) {
    margin: 20px 0 8px;
    font-size: 15px;
    line-height: 24px;
    font-weight: 600;
    color: var(--ink);
  }

  > :deep(:first-child) {
    margin-top: 0;
  }

  :deep(ul), :deep(ol) {
    padding-left: 20px;
  }

  :deep(li) {
    margin: 2px 0;
  }

  :deep(strong) {
    font-weight: 600;
    color: var(--ink);
  }

  :deep(a) {
    color: var(--ink);
    text-decoration: underline;
    text-decoration-color: var(--hairline);
    text-underline-offset: 2px;

    &:hover {
      color: var(--accent-text);
    }
  }

  :deep(code) {
    font-family: var(--font-mono);
    font-size: 13px;
  }

  :deep(pre) {
    padding: 12px;
    overflow-x: auto;
    background: #FAFAFA;
    border-radius: 8px;
  }

  :deep(blockquote) {
    padding-left: 12px;
    border-left: 2px solid var(--hairline);
  }

  :deep(hr) {
    margin: 16px 0;
    border: 0;
    border-top: 1px solid var(--rule);
  }
}

</style>
