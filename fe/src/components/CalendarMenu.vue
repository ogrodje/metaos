<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { IconDefinition } from '@fortawesome/fontawesome-svg-core'
import { faGoogle, faMicrosoft, faYahoo } from '@fortawesome/free-brands-svg-icons'
import { faCalendarPlus, faCopy } from '@fortawesome/free-regular-svg-icons'
import { faArrowUpRightFromSquare, faCheck, faDownload } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import type { CalendarLink } from '@/calendar'
import { withTimeout } from '@/usage'
import { useLocale } from '@/useLocale'

// Add to calendar: the events as a calendar to subscribe to, or one event alone to add (calendar.ts), in whichever
// calendar the visitor has, as no one link does it for all. Google Calendar, Outlook on the web and Yahoo each take
// their own address, which asks there (Outlook's two, as work and personal accounts sign in apart); the app on the
// device, Apple's or Outlook, opens webcal, or an event's file; and anything else takes the calendar's address, copied.
// A button that shows and hides a few links, which Tab goes through, rather than a menu, as the user's is (UserMenu.vue).
const props = withDefaults(defineProps<{
  // The ways in, in the order they're listed (subscribeLinks or eventLinks)
  links: CalendarLink[]
  // The calendar's https address, to copy; none for an event alone
  copy?: string
  // Which of the button's edges the links open from
  align?: 'left' | 'right'
}>(), { copy: undefined, align: 'right' })

const { t } = useLocale()

const ICONS: Record<CalendarLink['kind'], IconDefinition> = {
  google: faGoogle, outlook365: faMicrosoft, outlookCom: faMicrosoft, yahoo: faYahoo, app: faCalendarPlus, file: faDownload,
}

const TITLES: Record<CalendarLink['kind'], () => string> = {
  google: () => t('events.calendar.google'),
  outlook365: () => t('events.calendar.outlook365'),
  outlookCom: () => t('events.calendar.outlookCom'),
  yahoo: () => t('events.calendar.yahoo'),
  app: () => t('events.calendar.app'),
  file: () => t('events.calendar.file'),
}
const id = useId()

const root = ref<HTMLElement>()
const toggle = ref<HTMLButtonElement>()
const open = ref(false)

const copied = ref(false)
// What Copy did, for screen readers
const copyStatus = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function copyAddress() {
  const address = props.copy
  if (!address) return
  clearTimeout(copyTimer)
  try {
    await withTimeout(navigator.clipboard.writeText(address))
    copied.value = true
    copyStatus.value = t('events.calendar.copiedStatus')
  } catch (error) {
    // Shown instead, to copy by hand
    console.error("The calendar's address was not copied", error)
    copied.value = false
    window.prompt(t('events.calendar.copy'), address)
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
    <div v-if="open" :id="`${id}-links`" class="links" :class="align">
      <p class="label">{{ t('events.calendar.where') }}</p>
      <a v-for="link in links" :key="link.kind" class="item" :href="link.href" :target="link.external ? '_blank' : undefined"
         :rel="link.external ? 'noopener' : undefined" @click="open = false">
        <FontAwesomeIcon :icon="ICONS[link.kind]"/>{{ TITLES[link.kind]() }}<FontAwesomeIcon v-if="link.external" class="external" :icon="faArrowUpRightFromSquare"/>
      </a>
      <template v-if="copy">
        <div class="rule" aria-hidden="true"></div>
        <button type="button" class="item" :class="{ copied }" @click="copyAddress">
          <FontAwesomeIcon :icon="copied ? faCheck : faCopy"/>{{ copied ? t('events.calendar.copied') : t('events.calendar.copyAddress') }}
        </button>
      </template>
    </div>
    <span class="visually-hidden" role="status">{{ copyStatus }}</span>
  </div>
</template>

<style scoped lang="scss">
.calendar {
  position: relative;
  display: inline-flex;
}

// Under the button, at its right edge, as the user's menu is, or its left; never wider than a phone leaves it
.links {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;

  &.left {
    right: auto;
    left: 0;
  }

  z-index: 20;
  // As wide as its longest line, which breaks only where the screen is narrower, whatever a heading's actions do
  width: max-content;
  white-space: normal;
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
