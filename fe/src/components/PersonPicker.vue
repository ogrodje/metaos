<script setup lang="ts">
import { computed, onMounted, ref, useId, watch } from 'vue'
import { findPeople } from '@/accounts'
import { useLocale } from '@/useLocale'

/** Someone a picker offers: their name, and who they are, which tells two of the same name apart */
export interface Pickable {
  id: string
  name: string
  bio?: string | null
}

// A person picked by name as it's typed, from [people], e.g. those the page lists: a combobox, whose ↑ and ↓ go
// through who it found, ↵ picks one and Esc gives up. Before anything's typed it offers those with [suggest]'s name,
// e.g. an account's. With [free], what's typed may also be taken as a name, for someone who isn't in MetaOS.
const props = defineProps<{
  people: Pickable[]
  // What it picks for, e.g. Link maja.k to
  label: string
  suggest?: string | null
  // While the page does what the pick is for, and what it says then (Linking… by default)
  busy?: boolean
  busyText?: string
  // Whether what's typed may be taken as a name, for someone who isn't among [people]
  free?: boolean
}>()

const emit = defineEmits<{
  pick: [person: Pickable]
  write: [name: string]
  cancel: []
}>()

const { t } = useLocale()
const id = useId()
const input = ref<HTMLInputElement>()
const text = ref('')
const active = ref(0)

const typed = computed(() => text.value.trim() !== '')
const options = computed(() => findPeople(props.people, typed.value ? text.value : (props.suggest ?? '')))
const expanded = computed(() => options.value.length > 0)

// The first again as what's typed changes
watch(options, () => {
  active.value = 0
})

function optionId(index: number) {
  return `${id}-option-${index}`
}

function move(by: number) {
  if (!options.value.length) return
  active.value = (active.value + by + options.value.length) % options.value.length
  document.getElementById(optionId(active.value))?.scrollIntoView({ block: 'nearest' })
}

function pick(person: Pickable | undefined) {
  if (person && !props.busy) emit('pick', person)
}

// What's typed, as a name, where it may be one
const written = computed(() => (props.free && typed.value ? text.value.trim() : null))

function write() {
  if (written.value && !props.busy) emit('write', written.value)
}

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'ArrowDown') move(1)
  else if (event.key === 'ArrowUp') move(-1)
  else if (event.key === 'Enter') {
    if (options.value.length) pick(options.value[active.value])
    else write()
  }
  else if (event.key === 'Escape') {
    // Its own, not the page's
    event.stopPropagation()
    if (text.value && !props.busy) text.value = ''
    else emit('cancel')
  } else return
  event.preventDefault()
}

onMounted(() => input.value?.focus())
</script>

<template>
  <div class="picker">
    <div class="field">
      <input ref="input" v-model="text" class="input" type="text" role="combobox" autocomplete="off" spellcheck="false"
             :aria-label="label" :aria-expanded="expanded" :aria-controls="`${id}-options`" aria-autocomplete="list"
             :aria-activedescendant="expanded ? optionId(active) : undefined" :aria-describedby="`${id}-hint`"
             :readonly="busy" :placeholder="t('accounts.picker.placeholder')" @keydown="onKeydown"/>
      <button type="button" class="button" :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</button>
    </div>

    <p :id="`${id}-hint`" class="hint" aria-live="polite">
      <template v-if="busy">{{ busyText ?? t('accounts.linking') }}</template>
      <template v-else-if="typed && !options.length && free">{{ t('accounts.picker.noOneWrite') }}</template>
      <template v-else-if="typed && !options.length">{{ t('accounts.picker.noOne') }}</template>
      <template v-else-if="!typed && options.length">{{ t('accounts.picker.matching', { name: suggest }) }}</template>
      <template v-else-if="!typed">{{ t('accounts.picker.typeName') }}</template>
    </p>

    <ul v-show="expanded" :id="`${id}-options`" class="options" role="listbox" :aria-label="label">
      <li v-for="(person, index) in options" :id="optionId(index)" :key="person.id" role="option"
          :aria-selected="index === active" :class="{ active: index === active }"
          @mousedown.prevent @mousemove="active = index" @click="pick(person)">
        <span class="name">{{ person.name }}</span>
        <span v-if="person.bio" class="bio">{{ person.bio }}</span>
      </li>
    </ul>

    <button v-if="written" type="button" class="text-button write" :aria-disabled="busy || undefined" @click="write">
      {{ t('accounts.picker.write', { name: written }) }}
    </button>
  </div>
</template>

<style scoped lang="scss">

.picker {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}

.field {
  display: flex;
  gap: 8px;

  .input {
    flex: 1;
    padding: 0 12px;
  }

  .button:disabled {
    color: var(--muted);
    cursor: default;
  }

  .button:focus-visible {
    outline: 2px solid var(--accent-text);
    outline-offset: 2px;
  }
}

.write {
  justify-self: start;
  font-size: var(--text-small);
}

.hint {
  min-height: 20px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);

  &:empty {
    display: none;
  }
}

// Who it found, in the page's flow under the field, so it fits a phone
.options {
  max-height: 296px;
  overflow-y: auto;
  padding: 4px;
  list-style: none;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);

  li {
    display: flex;
    flex-direction: column;
    padding: 6px 10px;
    border-radius: var(--radius-sm);
    cursor: pointer;

    // ↑ and ↓ move it, as does the pointer
    &.active {
      background: var(--hover);
      box-shadow: inset 2px 0 0 var(--accent);
    }
  }

  .name {
    font-weight: 500;
  }

  .bio {
    overflow: hidden;
    font-size: var(--text-caption);
    line-height: 18px;
    color: var(--muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

</style>
