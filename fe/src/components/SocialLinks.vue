<script setup lang="ts">
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { networks } from '@/brands'
import { type Network, type Person, socialLinks } from '@/people'
import { useLocale } from '@/useLocale'

// Where a person is online, each in its brand's colour when pointed at
const props = defineProps<{
  person: Pick<Person, 'name' | 'homepageUrl' | 'linkedinUrl' | 'twitterUrl' | 'instagramUrl' | 'facebookUrl'>
}>()

const { t } = useLocale()

// Named, as a list of people has a row of these icons each
function label(network: Network) {
  const name = props.person.name
  return network === 'Website' ? t('people.links.website', { name }) : t('people.links.on', { name, network })
}
</script>

<template>
  <a v-for="link in socialLinks(person)" :key="link.network" class="icon-link brand-link" :href="link.url"
     :title="label(link.network)" :aria-label="label(link.network)" :style="{ '--brand': networks[link.network].color }">
    <FontAwesomeIcon :icon="networks[link.network].icon"/>
  </a>
</template>
