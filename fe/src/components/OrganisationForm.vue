<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import MeetupBadge from '@/components/MeetupBadge.vue'
import RegionSelect from '@/components/RegionSelect.vue'
import type { Region } from '@/events'
import { errorMessage, graphql } from '@/graphql'
import { src } from '@/images'
import {
  CREATE_ORGANISATION, EVENT_LINKS, isColor, type Organisation, type OrganisationInput, type OrganisationTier, tierName,
  UPDATE_ORGANISATION,
} from '@/organisations'
import { useLocale } from '@/useLocale'

// An organisation, created (createOrganisation) or changed (updateOrganisation), as create_organisation and
// update_organisation do over MCP: what it is and how it supports Ogrodje, where it's online, where it announces its
// events, which the scrapers read, with its badge's colours on the Events page, and its details for invoices. Its contact
// there is changed over MCP, so it's left as it is. Empty clears a field.
const props = defineProps<{ organisation?: Organisation }>()

const emit = defineEmits<{
  saved: [organisation: Organisation]
  close: []
}>()

const { t } = useLocale()
const id = useId()

type TextField = Exclude<keyof OrganisationInput, 'tier' | 'collectsEvents' | 'archived' | 'region'>

const TEXT_FIELDS: TextField[] = [
  'name', 'description', 'supportStartedAt', 'supportEndedAt', 'websiteUrl', 'linkedinUrl', 'twitterUrl', 'instagramUrl',
  'facebookUrl', 'vatId', 'companyId', 'address', 'meetupUrl', 'eventbriteUrl', 'icalUrl', 'lumaUrl', 'discordUrl',
  'kompotUrl', 'mainColor', 'backgroundColor', 'venueName', 'venueAddress',
]

const text = ref(Object.fromEntries(TEXT_FIELDS.map((field) => [field, props.organisation?.[field] ?? ''])) as Record<TextField, string>)
const tier = ref<OrganisationTier>(props.organisation?.tier ?? 'NONE')
const collectsEvents = ref(props.organisation?.collectsEvents ?? false)
// Its region when the team gave it one; else Automatic, what its venue tells
const region = ref<Region | ''>(props.organisation?.regionManual ? props.organisation.region ?? '' : '')

const TIER_OPTIONS: OrganisationTier[] = ['NONE', 'SUPPORTER', 'PARTNER']

// Its online links, as SocialLinks shows them
const LINKS: { field: TextField, name: string | null }[] = [
  { field: 'websiteUrl', name: null },
  { field: 'linkedinUrl', name: 'LinkedIn' },
  { field: 'twitterUrl', name: 'X' },
  { field: 'instagramUrl', name: 'Instagram' },
  { field: 'facebookUrl', name: 'Facebook' },
]

// Its badge as it'll be, with its logo
const badge = computed(() => ({
  name: text.value.name.trim() || t('organisationForm.namePlaceholder'),
  logo: props.organisation?.logo ? src(props.organisation.logo, 40) : null,
  mainColor: isColor(text.value.mainColor.trim()) ? text.value.mainColor.trim() : null,
  backgroundColor: isColor(text.value.backgroundColor.trim()) ? text.value.backgroundColor.trim() : null,
}))

const nameField = ref<HTMLInputElement>()
onMounted(() => nameField.value?.focus())

const busy = ref(false)
const error = ref<string>()

async function save() {
  if (busy.value) return
  if (!text.value.name.trim()) {
    error.value = t('organisationForm.nameRequired')
    return
  }
  const colors = [text.value.mainColor, text.value.backgroundColor].map((color) => color.trim())
  if (colors.some((color) => color && !isColor(color))) {
    error.value = t('organisationForm.badColor')
    return
  }
  busy.value = true
  error.value = undefined
  const input: OrganisationInput = { tier: tier.value, collectsEvents: collectsEvents.value }
  for (const field of TEXT_FIELDS) {
    const value = text.value[field].trim()
    // A new one gets only what's given; a change clears what was emptied
    if (value || props.organisation) input[field] = value
  }
  if (region.value || props.organisation) input.region = region.value
  try {
    const { organisation } = props.organisation
      ? await graphql<{ organisation: Organisation }>(UPDATE_ORGANISATION, { id: props.organisation.id, input })
      : await graphql<{ organisation: Organisation }>(CREATE_ORGANISATION, { input })
    emit('saved', organisation)
    emit('close')
  } catch (e) {
    error.value = errorMessage(e, t)
  } finally {
    busy.value = false
  }
}

// A colour field's picker, beside its text, which may be empty: the picker can't
function picked(field: 'mainColor' | 'backgroundColor', value: string) {
  text.value[field] = value
}
</script>

<template>
  <form class="organisation-form" :aria-labelledby="`${id}-title`" @submit.prevent="save" @keydown.esc.prevent="emit('close')">
    <h2 :id="`${id}-title`" class="form-title">{{ organisation ? t('organisationForm.edit', { name: organisation.name }) : t('organisationForm.create') }}</h2>

    <section class="part" :aria-labelledby="`${id}-about`">
      <h3 :id="`${id}-about`" class="part-title">{{ t('organisationForm.about') }}</h3>
      <div class="fields">
        <label class="wide">
          <span class="label">{{ t('organisationForm.name') }}</span>
          <input ref="nameField" v-model="text.name" class="input" type="text" autocomplete="off" required>
        </label>
        <label class="wide">
          <span class="label">{{ t('organisationForm.description') }}</span>
          <textarea v-model="text.description" class="input" rows="2" lang="sl"/>
        </label>
        <label>
          <span class="label">{{ t('organisationForm.tier') }}</span>
          <select v-model="tier" class="input">
            <option v-for="each in TIER_OPTIONS" :key="each" :value="each">
              {{ each === 'NONE' ? t('organisationForm.noTier') : tierName(each, t) }}
            </option>
          </select>
        </label>
        <span/>
        <template v-if="tier !== 'NONE'">
          <label>
            <span class="label">{{ t('organisationForm.supportStartedAt') }}</span>
            <input v-model="text.supportStartedAt" class="input" type="date">
          </label>
          <label>
            <span class="label">{{ t('organisationForm.supportEndedAt') }}</span>
            <input v-model="text.supportEndedAt" class="input" type="date">
          </label>
        </template>
      </div>
    </section>

    <section class="part" :aria-labelledby="`${id}-online`">
      <h3 :id="`${id}-online`" class="part-title">{{ t('organisationForm.online') }}</h3>
      <div class="fields">
        <label v-for="link in LINKS" :key="link.field">
          <span class="label">{{ link.name ?? t('organisationForm.website') }}</span>
          <input v-model="text[link.field]" class="input" type="url" inputmode="url" placeholder="https://" autocomplete="off">
        </label>
      </div>
    </section>

    <section class="part" :aria-labelledby="`${id}-events`">
      <h3 :id="`${id}-events`" class="part-title">{{ t('organisationForm.events') }}</h3>
      <label class="check">
        <input v-model="collectsEvents" type="checkbox"> {{ t('organisationForm.collectsEvents') }}
      </label>
      <div class="fields">
        <label v-for="link in EVENT_LINKS" :key="link.key">
          <span class="label">{{ link.name }}</span>
          <input v-model="text[link.key]" class="input" :type="link.key === 'icalUrl' ? 'text' : 'url'" inputmode="url"
                 :placeholder="link.key === 'icalUrl' ? 'https:// · webcal://' : 'https://'" autocomplete="off">
        </label>
        <label v-for="field in (['backgroundColor', 'mainColor'] as const)" :key="field">
          <span class="label">{{ t(`organisationForm.${field}`) }}</span>
          <span class="color">
            <input type="color" class="swatch" :value="isColor(text[field]) ? text[field] : (field === 'mainColor' ? '#ffffff' : '#000000')"
                   :aria-label="t(`organisationForm.${field}`)" @input="picked(field, ($event.target as HTMLInputElement).value)">
            <input v-model="text[field]" class="input" type="text" placeholder="#rrggbb" maxlength="7" spellcheck="false" autocomplete="off">
          </span>
        </label>
      </div>
      <div class="fields">
        <label>
          <span class="label">{{ t('organisation.venueName') }}</span>
          <input v-model="text.venueName" class="input" type="text" autocomplete="off" :placeholder="t('organisationForm.venuePlaceholder')">
        </label>
        <label>
          <span class="label">{{ t('organisation.venueAddress') }}</span>
          <input v-model="text.venueAddress" class="input" type="text" autocomplete="off" :placeholder="t('organisationForm.venueAddressPlaceholder')">
        </label>
      </div>
      <p class="hint">{{ t('organisationForm.venueHint') }}</p>
      <div class="fields">
        <label>
          <span class="label">{{ t('organisation.region') }}</span>
          <RegionSelect v-model="region" :found="organisation && !organisation.regionManual ? organisation.region : null"/>
        </label>
      </div>
      <p class="hint">{{ t('organisationForm.regionHint') }}</p>
      <p class="preview">
        <span class="label">{{ t('organisationForm.badge') }}</span>
        <MeetupBadge :meetup="badge"/>
      </p>
      <p class="hint">{{ t('organisationForm.eventsHint') }}</p>
    </section>

    <section class="part" :aria-labelledby="`${id}-finance`">
      <h3 :id="`${id}-finance`" class="part-title">{{ t('organisation.finance') }}</h3>
      <div class="fields">
        <label>
          <span class="label">{{ t('organisation.vatId') }}</span>
          <input v-model="text.vatId" class="input" type="text" autocomplete="off" spellcheck="false">
        </label>
        <label>
          <span class="label">{{ t('organisation.companyId') }}</span>
          <input v-model="text.companyId" class="input" type="text" autocomplete="off" spellcheck="false">
        </label>
        <label class="wide">
          <span class="label">{{ t('organisation.address') }}</span>
          <input v-model="text.address" class="input" type="text" autocomplete="off">
        </label>
      </div>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="buttons">
      <button type="submit" class="button primary" :aria-disabled="busy || undefined">
        {{ busy ? t('organisationForm.saving') : organisation ? t('common.save') : t('organisationForm.submit') }}
      </button>
      <button type="button" class="button quiet" :disabled="busy" @click="emit('close')">{{ t('common.cancel') }}</button>
    </div>
  </form>
</template>

<style scoped lang="scss">
.organisation-form {
  display: grid;
  gap: 20px;
  max-width: 680px;
  padding: 8px 0 24px;
}

.form-title {
  font-size: var(--text-section);
  line-height: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.part {
  display: grid;
  gap: 10px;
}

.part-title {
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

label {
  display: grid;
  gap: 4px;
  min-width: 0;
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

.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;
}

// A colour: its picker, then its text, which may be empty
.color {
  display: flex;
  gap: 8px;

  .input {
    flex: 1;
    font-family: var(--font-mono);
  }
}

.swatch {
  flex: none;
  width: 44px;
  height: 44px;
  padding: 4px;
  background: #FFFFFF;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.preview {
  display: flex;
  align-items: center;
  gap: 10px;
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
