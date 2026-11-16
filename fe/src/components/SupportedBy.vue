<script setup lang="ts">
import { computed } from 'vue'
import { I18nT } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { organisationPath, type Supporter } from '@/organisations'
import { useLocale } from '@/useLocale'

// Who supported an episode's production, as a line: Supported by Kaldi and Koofr. Each leads to its website, or for the
// team to its page in MetaOS.
const props = defineProps<{
  organisations: Pick<Supporter, 'id' | 'name' | 'websiteUrl'>[]
  team?: boolean
}>()

const { t, dateLocale, path } = useLocale()

// The names and what's between them, in the page's language: A, B and C
const parts = computed(() => {
  const names = props.organisations.map((organisation) => organisation.name)
  let i = 0
  return new Intl.ListFormat(dateLocale.value, { type: 'conjunction' }).formatToParts(names).map((part) =>
    (part.type === 'element' ? { organisation: props.organisations[i++]!, text: part.value } : { organisation: null, text: part.value }))
})
</script>

<template>
  <p v-if="organisations.length" class="supported-by">
    <I18nT keypath="episode.supportedBy" scope="global">
      <template #names>
        <template v-for="(part, i) in parts" :key="i">
          <RouterLink v-if="part.organisation && team" :to="path(organisationPath(part.organisation))">{{ part.text }}</RouterLink>
          <a v-else-if="part.organisation?.websiteUrl" :href="part.organisation.websiteUrl" target="_blank" rel="noopener">{{ part.text }}</a>
          <template v-else>{{ part.text }}</template>
        </template>
      </template>
    </I18nT>
  </p>
</template>

<style scoped lang="scss">

.supported-by {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);

  a {
    color: var(--ink);
    font-weight: 500;

    &:hover {
      text-decoration: underline;
      text-underline-offset: 3px;
    }
  }
}
</style>
