<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { faGoogle, faMicrosoft } from '@fortawesome/free-brands-svg-icons'
import { faCalendarPlus, faCopy } from '@fortawesome/free-regular-svg-icons'
import { faArrowUpRightFromSquare, faCheck } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { googleCalendarUrl, outlookCalendarUrl, webcalUrl } from '@/calendar'
import { withTimeout } from '@/usage'
import { useLocale } from '@/useLocale'

// Add to calendar: the events as a calendar to subscribe to (calendar.ts), in whichever calendar the visitor has, as
// no one link does it for all. Google Calendar and Outlook on the web each take their own address, which asks there
// to add it (Outlook's two, as work and personal accounts sign in apart); the app on the device, Apple's or Outlook,
// opens webcal; and anything else takes the address, copied. A button that shows and hides a few links, which Tab
// goes through, rather than a menu, as the user's is (UserMenu.vue).
const props = defineProps<{
  // The calendar's https address, with the page's filters
  url: string
  // Its name, which Outlook lists it by
  name: string
}>()

const { t } = useLocale()
const id = useId()

const root = ref<HTMLElement>()
const toggle = ref<HTMLButtonElement>()
const open = ref(false)

const copied = ref(false)
// What Copy did, for screen readers
const copyStatus = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function copy() {
  clearTimeout(copyTimer)
  try {
    await withTimeout(navigator.clipboard.writeText(props.url))
    copied.value = true
    copyStatus.value = t('events.calendar.copiedStatus')
  } catch (error) {
    // Shown instead, to copy by hand
    console.error("The calendar's address was not copied", error)
    copied.value = false
    window.prompt(t('events.calendar.copy'), props.url)
  }
  copyTimer = setTimeout(() => {
    copied.value = false
    copyStatus.value = ''
  }, 2000)
}

function close() {
  // Back on the button, as what had the focus goes with the links
  const focused = root.value?.contains(document.activeElement) && document.activeElement !== toggle.value
  open.value = false
  if (focused) void nextTick(() => toggle.value?.focus())
}

// Closed by a click anywhere else, or Esc
function onDocumentClick(event: MouseEvent) {
  if (!root.value?.contains(event.target as Node)) open.value = false
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) close()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
  clearTimeout(copyTimer)
})
</script>

<template>
  <div ref="root" class="calendar">
    <button ref="toggle" type="button" class="button" :aria-expanded="open" :aria-controls="`${id}-links`" @click="open = !open">
      <FontAwesomeIcon :icon="faCalendarPlus"/>{{ t('events.calendar.add') }}
    </button>
    <div v-if="open" :id="`${id}-links`" class="links">
      <p class="label">{{ t('events.calendar.where') }}</p>
      <a class="item" :href="googleCalendarUrl(url)" target="_blank" rel="noopener" @click="open = false">
        <FontAwesomeIcon :icon="faGoogle"/>{{ t('events.calendar.google') }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
      <a class="item" :href="outlookCalendarUrl(url, name, 'office')" target="_blank" rel="noopener" @click="open = false">
        <FontAwesomeIcon :icon="faMicrosoft"/>{{ t('events.calendar.outlook365') }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
      <a class="item" :href="outlookCalendarUrl(url, name, 'live')" target="_blank" rel="noopener" @click="open = false">
        <FontAwesomeIcon :icon="faMicrosoft"/>{{ t('events.calendar.outlookCom') }}<FontAwesomeIcon class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
      <a class="item" :href="webcalUrl(url)" @click="open = false">
        <FontAwesomeIcon :icon="faCalendarPlus"/>{{ t('events.calendar.app') }}
      </a>
      <div class="rule" aria-hidden="true"></div>
      <button type="button" class="item" :class="{ copied }" @click="copy">
        <FontAwesomeIcon :icon="copied ? faCheck : faCopy"/>{{ copied ? t('events.calendar.copied') : t('events.calendar.copyAddress') }}
      </button>
    </div>
    <span class="visually-hidden" role="status">{{ copyStatus }}</span>
  </div>
</template>

<style scoped lang="scss">
.calendar {
  position: relative;
  display: inline-flex;
}

// Under the button, at its right edge, as the user's menu is; never wider than a phone leaves it
.links {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  min-width: 264px;
  max-width: calc(100vw - 32px);
  padding: 6px;
  background: #FFFFFF;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-menu);
}

.label {
  padding: 6px 10px 2px;
  font-size: var(--text-micro);
  line-height: 16px;
  font-weight: 600;
  letter-spacing: .06em;
  text-transform: uppercase;
  color: var(--muted);
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: none;
  font-size: var(--text-body);
  line-height: 20px;
  color: var(--ink);
  text-align: left;
  white-space: nowrap;
  cursor: pointer;

  &:hover {
    background: var(--hover);
    color: var(--ink);
  }

  &:focus-visible {
    outline-offset: -2px;
  }

  svg {
    flex: none;
    width: 16px;
    color: var(--muted);
  }

  // Opens in a tab of its own
  .external {
    width: 12px;
    margin-left: auto;
  }

  &.copied svg {
    color: var(--success);
  }
}

.rule {
  margin: 6px 0;
  border-top: 1px solid var(--rule);
}

// On a phone the button is at the left, under the heading, so its links open from there
@media (max-width: 640px) {
  .links {
    right: auto;
    left: 0;
  }
}
</style>
