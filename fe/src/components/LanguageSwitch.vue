<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n, useSwitchLocalePath } from '#imports'
import { rememberLocale } from '@/localeChoice'

// The UI's language, Slovenian or English, as links to the same page in the other: a link, so it works before the page
// is hydrated too, and the address says the language (OG-58). Choosing one remembers it in this browser, which the
// pages are opened in afterwards (plugins/locale.client.ts). Each language is named in itself.

const { locale, locales } = useI18n()
const switchLocalePath = useSwitchLocalePath()

const languages = computed(() => locales.value.map((each) => ({
  code: each.code,
  name: each.name ?? each.code,
  path: switchLocalePath(each.code),
  current: each.code === locale.value,
})))
</script>

<template>
  <nav class="languages" :aria-label="$t('language.label')">
    <template v-for="(language, i) in languages" :key="language.code">
      <span v-if="i > 0" class="dot" aria-hidden="true">·</span>
      <span v-if="language.current" class="current" aria-current="true" :lang="language.code">{{ language.name }}</span>
      <RouterLink v-else :to="language.path" :lang="language.code" :hreflang="language.code"
                  @click="rememberLocale(language.code)">{{ language.name }}</RouterLink>
    </template>
  </nav>
</template>

<style scoped lang="scss">
.languages {
  display: inline-flex;
  align-items: baseline;
  gap: 0.4em;
  font-size: var(--text-small);
}

.current {
  color: var(--ink);
  font-weight: 500;
}

a {
  color: var(--muted);

  &:hover {
    color: var(--ink);
  }
}

.dot {
  color: var(--muted);
}
</style>
