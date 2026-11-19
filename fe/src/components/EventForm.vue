<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import RegionSelect from '@/components/RegionSelect.vue'
import {
  CREATE_EVENT, type EventInput, formTime, inputTime, type Region, type TeamEvent, UPDATE_EVENT,
} from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { useLocale } from '@/useLocale'

// An event of the team's own, added (createEvent) or changed wholly (updateEvent), as those a scraper found only hide
// and are picked: who it's by, its title, when it starts and ends (a day, and a time in Ljubljana where it has one),
// where it's announced, where it is and what it's about. Who it's by is chosen among the organisations that aren't
// archived, unless the page is the organisation's own (organisation). Its region is what its place tells, unless one is
// chosen.
const props = defineProps<{
  event?: TeamEvent
  organisation?: { id: string, name: string }
}>()

const emit = defineEmits<{
  saved: [event: TeamEvent]
  close: []
}>()

const { t } = useLocale()
const id = useId()

const start = formTime(props.event?.start ?? null, props.event?.hasStartTime ?? true)
const end = formTime(props.event?.end ?? null, props.event?.hasEndTime ?? true)
const form = ref({
  organisation: props.organisation?.id ?? props.event?.organisation.id ?? '',
  title: props.event?.title ?? '',
  startDay: start.day,
  startTime: start.time,
  endDay: end.day,
  endTime: end.time,
  url: props.event?.url ?? '',
  locationName: props.event?.locationName ?? '',
  locationAddress: props.event?.locationAddress ?? '',
  description: props.event?.description ?? '',
  region: (props.event?.regionManual ? props.event.region ?? '' : '') as Region | '',
})

// The organisations to choose from, by name
const organisations = ref<{ id: string, name: string }[]>()
const loadError = ref<string>()

const title = ref<HTMLInputElement>()
onMounted(async () => {
  title.value?.focus()
  if (props.organisation) return
  try {
    organisations.value = (await graphql<{ organisations: { id: string, name: string }[] }>(
      '{ organisations(archived: false) { id name } }')).organisations
  } catch (e) {
    loadError.value = errorMessage(e, t)
  }
})

// Its own organisation stays among them, should it be archived since
const choices = computed(() => {
  const listed = organisations.value ?? []
  const own = props.event?.organisation
  return own && !listed.some((each) => each.id === own.id) ? [own, ...listed] : listed
})

const busy = ref(false)
const error = ref<string>()

async function save() {
  if (busy.value) return
  const value = form.value
  if (!value.organisation || !value.title.trim() || !value.startDay) {
    error.value = t('eventForm.required')
    return
  }
  busy.value = true
  error.value = undefined
  const input: EventInput = {
    organisation: value.organisation,
    title: value.title.trim(),
    start: inputTime({ day: value.startDay, time: value.startTime }),
    end: inputTime({ day: value.endDay, time: value.endTime }),
    url: value.url.trim(),
    locationName: value.locationName.trim(),
    locationAddress: value.locationAddress.trim(),
    description: value.description.trim(),
    // A new one says its region only when chosen; a change clears one that was
    ...(value.region || props.event ? { region: value.region } : {}),
  }
  try {
    const { event } = props.event
      ? await graphql<{ event: TeamEvent }>(UPDATE_EVENT, { id: props.event.id, input })
      : await graphql<{ event: TeamEvent }>(CREATE_EVENT, { input })
    emit('saved', event)
    emit('close')
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="event-form" :aria-labelledby="`${id}-title`" @submit.prevent="save" @keydown.esc.prevent="emit('close')">
    <h3 :id="`${id}-title`" class="form-title">{{ event ? t('eventForm.edit') : t('eventForm.add') }}</h3>
    <div class="fields">
      <label v-if="!organisation" class="wide">
        <span class="label">{{ t('eventForm.organisation') }}</span>
        <select v-model="form.organisation" class="input" required>
          <option value="" disabled>{{ organisations ? t('eventForm.chooseOrganisation') : t('common.loading') }}</option>
          <option v-for="each in choices" :key="each.id" :value="each.id">{{ each.name }}</option>
        </select>
      </label>
      <label class="wide">
        <span class="label">{{ t('eventForm.title') }}</span>
        <input ref="title" v-model="form.title" class="input" type="text" lang="sl" autocomplete="off" required>
      </label>
      <div class="when" role="group" :aria-labelledby="`${id}-start`">
        <span :id="`${id}-start`" class="label">{{ t('eventForm.start') }}</span>
        <input v-model="form.startDay" class="input" type="date" required :aria-label="t('eventForm.startDay')">
        <input v-model="form.startTime" class="input" type="time" :aria-label="t('eventForm.startTime')">
      </div>
      <div class="when" role="group" :aria-labelledby="`${id}-end`">
        <span :id="`${id}-end`" class="label">{{ t('eventForm.end') }}</span>
        <input v-model="form.endDay" class="input" type="date" :aria-label="t('eventForm.endDay')">
        <input v-model="form.endTime" class="input" type="time" :aria-label="t('eventForm.endTime')">
      </div>
      <label class="wide">
        <span class="label">{{ t('eventForm.url') }}</span>
        <input v-model="form.url" class="input" type="url" inputmode="url" placeholder="https://" autocomplete="off">
      </label>
      <label>
        <span class="label">{{ t('eventForm.locationName') }}</span>
        <input v-model="form.locationName" class="input" type="text" lang="sl" autocomplete="off">
      </label>
      <label>
        <span class="label">{{ t('eventForm.locationAddress') }}</span>
        <input v-model="form.locationAddress" class="input" type="text" lang="sl" autocomplete="off">
      </label>
      <label>
        <span class="label">{{ t('eventForm.region') }}</span>
        <RegionSelect v-model="form.region" :found="event && !event.regionManual ? event.region : null"/>
      </label>
      <label class="wide">
        <span class="label">{{ t('eventForm.description') }}</span>
        <textarea v-model="form.description" class="input" rows="3" lang="sl"/>
      </label>
    </div>
    <p class="hint">{{ t('eventForm.hint') }}</p>
    <p v-if="loadError" class="error" role="alert">{{ loadError }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="buttons">
      <button type="submit" class="button primary" :aria-disabled="busy || undefined">
        {{ busy ? t('eventForm.saving') : event ? t('common.save') : t('eventForm.submit') }}
      </button>
      <button type="button" class="button quiet" :disabled="busy" @click="emit('close')">{{ t('common.cancel') }}</button>
    </div>
  </form>
</template>

<style scoped lang="scss">
.event-form {
  display: grid;
  gap: 10px;
  max-width: 680px;
  padding: 16px 0;
}

.form-title {
  font-size: var(--text-body);
  line-height: 22px;
  font-weight: 600;
}

.fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;

  .wide {
    grid-column: 1 / -1;
  }
}

label, .when {
  display: grid;
  gap: 4px;
  min-width: 0;
}

// Its day, and its time where it has one
.when {
  grid-template-columns: minmax(0, 1fr) 110px;
  column-gap: 8px;

  .label {
    grid-column: 1 / -1;
  }
}

.label {
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  color: var(--muted);
}

textarea.input {
  padding: 10px;
  line-height: 22px;
  resize: vertical;
}

.hint {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
}

.error {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

.buttons {
  display: flex;
  gap: 8px;
}

@media (max-width: 640px) {
  .fields {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
