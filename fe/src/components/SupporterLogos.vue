<script setup lang="ts">
import { computed, ref } from 'vue'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import { graphql } from '@/graphql'
import { src, srcset } from '@/images'
import { byTier, isWordmark, siteName, SUPPORTER_FIELDS, type Supporter, tierName } from '@/organisations'
import { useFirstLoad } from '@/ssr'
import { useLocale } from '@/useLocale'

// Ogrodje's supporters and partners, by tier, as ogrodje.si listed them: large on the home page, as icons in the footer
// of the other pages. Each logo leads to the organisation's website, with its name beside it but for a word mark,
// which has the name in it. Rendered on the server, as the page they're on is, once for both (the same key).
const props = defineProps<{ large?: boolean }>()

const { t } = useLocale()

const supporters = ref<Supporter[]>()

async function fetchSupporters() {
  return (await graphql<{ supporters: Supporter[] }>(`{ supporters { ${SUPPORTER_FIELDS} } }`)).supporters
}

// Nothing rather than an error: they're the page's edge, not what it's for
const first = await useFirstLoad('supporters', fetchSupporters)
supporters.value = first.data

useLiveRefresh(async () => {
  supporters.value = keepUnchanged(supporters.value, await fetchSupporters())
})

const tiers = computed(() => byTier(supporters.value ?? []))

// The logo's height, which its width follows: a word mark as tall as a square one would look too big
const height = computed(() => (props.large ? 56 : 20))
</script>

<template>
  <section v-if="tiers.length" class="supporters" :class="{ large }" :aria-label="t('organisations.supportersLabel')">
    <div v-for="group in tiers" :key="group.tier" class="tier">
      <component :is="large ? 'h2' : 'p'" class="tier-name">{{ tierName(group.tier, t) }}</component>
      <ul>
        <li v-for="organisation in group.organisations" :key="organisation.id">
          <component :is="organisation.websiteUrl ? 'a' : 'span'" class="supporter" :href="organisation.websiteUrl ?? undefined"
                     :title="organisation.websiteUrl ? t('organisations.visit', { name: organisation.name, site: siteName(organisation.websiteUrl) }) : undefined"
                     :target="organisation.websiteUrl ? '_blank' : undefined" :rel="organisation.websiteUrl ? 'noopener' : undefined">
            <img v-if="organisation.logo" :src="src(organisation.logo, large ? 320 : 160)" :srcset="srcset(organisation.logo)"
                 :sizes="`${Math.ceil(height * organisation.logo.width / organisation.logo.height)}px`"
                 :height="height" :width="Math.round(height * organisation.logo.width / organisation.logo.height)"
                 :class="{ wordmark: isWordmark(organisation.logo) }" :alt="isWordmark(organisation.logo) ? organisation.name : ''">
            <span v-if="!isWordmark(organisation.logo)" class="name">{{ organisation.name }}</span>
          </component>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped lang="scss">

// The footer's: a line for each tier, its name then its logos, quiet as the rest of the footer
.supporters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 32px;
}

.tier {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
}

.tier-name {
  font-size: var(--text-caption);
  line-height: 20px;
  color: var(--muted);
}

ul {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
  padding: 0;
  list-style: none;
}

.supporter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
  white-space: nowrap;

  img {
    display: block;
    width: auto;
    height: 20px;
    max-width: 120px;
    object-fit: contain;

    // A word mark has the name in it, so it's as tall as the names beside the others
    &.wordmark {
      height: 16px;
    }
  }
}

a.supporter:hover {
  color: var(--ink);
}

// The home page's: a section for each tier, under its heading, the logos large in a row that wraps
.large {
  flex-direction: column;
  gap: 32px;

  .tier {
    flex-direction: column;
    align-items: stretch;
    gap: 0;
  }

  .tier-name {
    padding-bottom: 12px;
    border-bottom: 1px solid var(--rule);
    font-size: var(--text-section);
    line-height: 26px;
    font-weight: 600;
    color: var(--ink);
  }

  ul {
    gap: 8px 40px;
    padding-top: 20px;
  }

  .supporter {
    gap: 12px;
    padding: 4px 0;
    font-size: var(--text-lead);
    line-height: 24px;
    font-weight: 500;
    color: var(--ink);

    img {
      height: 56px;
      max-width: 200px;

      // A word mark has the name in it, so it's as tall as text beside a square logo
      &.wordmark {
        height: 36px;
      }
    }
  }

  a.supporter:hover .name {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}
</style>
