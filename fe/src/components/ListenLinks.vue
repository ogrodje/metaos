<script setup lang="ts">
import { ref } from 'vue'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faCheck } from '@fortawesome/free-solid-svg-icons'
import { brands } from '@/brands'
import { type Linked, type Platform, links } from '@/episodes'
import { withTimeout } from '@/usage'

// Where to listen to an episode, each in its brand's colour when pointed at: icons in a list, or buttons that also say
// where, e.g. on its page, where the team also copies each link with the icon beside it (copyable); on Patreon its
// video only, unless allPatreon (flag patreon-links)
defineProps<{
  episode: Linked
  labelled?: boolean
  copyable?: boolean
  allPatreon?: boolean
}>()

// The link just copied, for its check, and what was done, for screen readers
const copied = ref<Platform>()
const status = ref('')
let timer: ReturnType<typeof setTimeout> | undefined

async function copy(platform: Platform, url: string) {
  clearTimeout(timer)
  try {
    await withTimeout(navigator.clipboard.writeText(url))
    copied.value = platform
    status.value = `Copied the ${platform} link`
  } catch (error) {
    // Shown instead, to copy by hand
    console.error(`The ${platform} link was not copied`, error)
    copied.value = undefined
    window.prompt(`Copy the ${platform} link`, url)
  }
  timer = setTimeout(() => {
    copied.value = undefined
    status.value = ''
  }, 2000)
}
</script>

<template>
  <template v-if="labelled">
    <div v-for="link in links(episode, allPatreon)" :key="link.platform" :class="{ cell: copyable }">
      <a class="button listen-button" :href="link.url" :style="{ '--brand': brands[link.platform].color }">
        <FontAwesomeIcon :icon="brands[link.platform].icon"/>{{ link.platform }}
      </a>
      <button v-if="copyable" type="button" class="copy" :class="{ copied: copied === link.platform }"
              :aria-label="copied === link.platform ? 'Copied' : `Copy the ${link.platform} link`"
              :title="copied === link.platform ? 'Copied' : 'Copy link'" @click="copy(link.platform, link.url)">
        <FontAwesomeIcon :icon="copied === link.platform ? faCheck : faCopy"/>
      </button>
    </div>
    <span v-if="copyable" class="visually-hidden" role="status">{{ status }}</span>
  </template>
  <template v-else>
    <a v-for="link in links(episode, allPatreon)" :key="link.platform" class="icon-link brand-link" :href="link.url"
       :title="link.platform" :aria-label="link.platform" :style="{ '--brand': brands[link.platform].color }">
      <FontAwesomeIcon :icon="brands[link.platform].icon"/>
    </a>
  </template>
</template>

<style scoped lang="scss">

// A button that says where, its icon in the brand's colour
.listen-button {
  width: 100%;
  min-height: 36px;
  padding: 0 10px;
  white-space: nowrap;

  svg {
    color: var(--brand);
  }
}

// The button, and the icon that copies its link just right of it, quiet until it's pointed at
.cell {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;

  .listen-button {
    flex: 1;
    min-width: 0;
  }
}

.copy {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: var(--icon-link);
  padding: 0;
  font-size: var(--text-small);
  color: var(--faint);
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
  cursor: pointer;

  &:hover {
    color: var(--ink);
    background: var(--hover);
  }

  &:focus-visible {
    color: var(--ink);
    outline: 2px solid var(--accent-text);
    outline-offset: -2px;
  }

  &.copied {
    color: var(--success);
  }

  // Bigger for fingers
  @media (pointer: coarse) {
    width: var(--icon-link);
  }
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
