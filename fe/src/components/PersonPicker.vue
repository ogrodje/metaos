<script setup lang="ts">
import { computed, onMounted, ref, useId, watch } from 'vue'
import { findPeople } from '@/accounts'

/** Someone a picker offers: their name, and who they are, which tells two of the same name apart */
export interface Pickable {
  id: string
  name: string
  bio?: string | null
}

// A person picked by name as it's typed, from [people], e.g. those the page lists: a combobox, whose ↑ and ↓ go
// through who it found, ↵ picks one and Esc gives up. Before anything's typed it offers those with [suggest]'s name,
// e.g. an account's.
const props = defineProps<{
  people: Pickable[]
  // What it picks for, e.g. Link maja.k to
  label: string
  suggest?: string | null
  // While the page does what the pick is for
  busy?: boolean
}>()

const emit = defineEmits<{
  pick: [person: Pickable]
  cancel: []
}>()

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

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'ArrowDown') move(1)
  else if (event.key === 'ArrowUp') move(-1)
  else if (event.key === 'Enter') pick(options.value[active.value])
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
             :readonly="busy" placeholder="Find a person by name" @keydown="onKeydown"/>
      <button type="button" class="button" :disabled="busy" @click="emit('cancel')">Cancel</button>
    </div>

    <p :id="`${id}-hint`" class="hint" aria-live="polite">
      <template v-if="busy">Linking…</template>
      <template v-else-if="typed && !options.length">No one by that name.</template>
      <template v-else-if="!typed && options.length">Matching {{ suggest }}:</template>
      <template v-else-if="!typed">Type a name.</template>
    </p>

    <ul v-show="expanded" :id="`${id}-options`" class="options" role="listbox" :aria-label="label">
      <li v-for="(person, index) in options" :id="optionId(index)" :key="person.id" role="option"
          :aria-selected="index === active" :class="{ active: index === active }"
          @mousedown.prevent @mousemove="active = index" @click="pick(person)">
        <span class="name">{{ person.name }}</span>
        <span v-if="person.bio" class="bio">{{ person.bio }}</span>
      </li>
    </ul>
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
