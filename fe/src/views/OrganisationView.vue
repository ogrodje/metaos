<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import EpisodeRow from '@/components/EpisodeRow.vue'
import Fact from '@/components/Fact.vue'
import FactGroup from '@/components/FactGroup.vue'
import FactPanel from '@/components/FactPanel.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NotFound from '@/components/NotFound.vue'
import OrganisationLogo from '@/components/OrganisationLogo.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { type Episode, EPISODE_FIELDS, LIST_PICTURE_FIELDS } from '@/episodes'
import { errorMessage, graphql } from '@/graphql'
import { type Image, IMAGE_FIELDS } from '@/images'
import { type Organisation, ORGANISATION_FIELDS, siteName, tierName, type Work, WORK_FIELDS, workPeriod } from '@/organisations'
import { personPath } from '@/people'
import { useAuthStore } from '@/stores/auth'
import { usePageTitle } from '@/title'
import { useLocale } from '@/useLocale'

/** An organisation with who works there and the episodes it supported, for its page */
interface OrganisationDetails extends Organisation {
  people: (Work & { person: { id: string, name: string, avatar: Image | null } })[]
  episodes: Episode[]
}

const auth = useAuthStore()
const route = useRoute()
const { t, dateLocale, path } = useLocale()

const id = String(route.params.id)

// Undefined while it loads; null when there's no such organisation
const organisation = ref<OrganisationDetails | null>()
const error = ref<string>()

let loads = 0

// The team's, as is the page (it renders in the browser only); quietly when MetaOS's data changes
async function load(quietly = false) {
  const load = ++loads
  if (!auth.team) return
  try {
    const data = await graphql<{ organisation: OrganisationDetails | null }>(`query ($id: ID!) {
      organisation(id: $id) {
        ${ORGANISATION_FIELDS}
        people { ${WORK_FIELDS} person { id name avatar { ${IMAGE_FIELDS} } } }
        episodes { ${EPISODE_FIELDS} ${LIST_PICTURE_FIELDS} }
      }
    }`, { id })
    if (load !== loads) return
    organisation.value = keepUnchanged(organisation.value, data.organisation)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = errorMessage(e, t)
  }
}

watch(() => auth.team, () => void load(), { immediate: true })
useLiveRefresh(() => load(true))

usePageTitle(() => organisation.value?.name)

// Who works there now, then who did
const current = computed(() => (organisation.value?.people ?? []).filter((work) => work.current))
const former = computed(() => (organisation.value?.people ?? []).filter((work) => !work.current))
const workLists = computed(() => [
  { id: 'people', title: t('organisation.people'), works: current.value },
  { id: 'former', title: t('organisation.former'), works: former.value },
].filter((list) => list.works.length))

// Its links, as SocialLinks shows a person's
const links = computed(() => organisation.value && {
  name: organisation.value.name,
  homepageUrl: organisation.value.websiteUrl,
  linkedinUrl: organisation.value.linkedinUrl,
  twitterUrl: organisation.value.twitterUrl,
  instagramUrl: organisation.value.instagramUrl,
  facebookUrl: organisation.value.facebookUrl,
})

const day = (date: string) =>
  new Date(`${date}T12:00:00Z`).toLocaleDateString(dateLocale.value, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

// How it supports Ogrodje, in a line under its name, e.g. Podporniki, since 1 Jan 2026
const support = computed(() => {
  const shown = organisation.value
  if (!shown || shown.tier === 'NONE') return null
  return shown.supporting ? tierName(shown.tier, t) : t('organisations.formerly', { tier: tierName(shown.tier, t) })
})

const hasFinance = computed(() => {
  const shown = organisation.value
  return !!shown && !!(shown.vatId || shown.companyId || shown.address || shown.contactPerson)
})
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('organisations.signInTitle')">
      {{ t('organisations.signInOne') }}
    </SignInPrompt>
    <p v-else-if="auth.ready && !auth.team" class="note">{{ t('organisations.staffOnly') }}</p>
    <LoadError v-else-if="error" :what="t('organisation.what')" :error="error" @retry="load()"/>
    <LoadingRows v-else-if="organisation === undefined" :rows="6" :label="t('organisation.loading')"/>
    <NotFound v-else-if="organisation === null" :title="t('organisation.notFound')">{{ t('organisation.notFoundText') }}</NotFound>

    <!-- What it is, as a person's page has who they are; at the right its support, links and finance details; under
         them who works there and the episodes it supported -->
    <div v-else class="workspace">
      <div class="lead">
        <header class="organisation">
          <OrganisationLogo :image="organisation.logo" :name="organisation.name" :size="96"/>
          <div class="title">
            <h1>{{ organisation.name }}</h1>
            <p v-if="support" class="meta">{{ support }}</p>
            <p v-if="organisation.description" class="subtitle">{{ organisation.description }}</p>
          </div>
        </header>
      </div>

      <FactPanel :label="t('organisation.about', { name: organisation.name })">
        <FactGroup v-if="organisation.tier !== 'NONE'" :title="t('organisation.support')">
          <dl>
            <Fact :label="t('organisation.tier')">{{ tierName(organisation.tier, t) }}</Fact>
            <Fact v-if="organisation.supportStartedAt" :label="t('organisation.since')">{{ day(organisation.supportStartedAt) }}</Fact>
            <Fact v-if="organisation.supportEndedAt" :label="t('organisation.until')">{{ day(organisation.supportEndedAt) }}</Fact>
            <Fact :label="t('organisation.listed')">{{ organisation.supporting ? t('organisation.listedYes') : t('organisation.listedNo') }}</Fact>
          </dl>
        </FactGroup>
        <FactGroup v-if="organisation.websiteUrl || organisation.linkedinUrl || organisation.twitterUrl || organisation.instagramUrl || organisation.facebookUrl">
          <dl>
            <Fact :label="t('person.online')"><span class="icons"><SocialLinks v-if="links" :person="links"/></span></Fact>
            <Fact v-if="organisation.websiteUrl" :label="t('person.website')">
              <a :href="organisation.websiteUrl">{{ siteName(organisation.websiteUrl) }}</a>
            </Fact>
          </dl>
        </FactGroup>
        <FactGroup v-if="hasFinance" :title="t('organisation.finance')">
          <dl>
            <Fact v-if="organisation.vatId" :label="t('organisation.vatId')"><span class="number">{{ organisation.vatId }}</span></Fact>
            <Fact v-if="organisation.companyId" :label="t('organisation.companyId')"><span class="number">{{ organisation.companyId }}</span></Fact>
            <Fact v-if="organisation.address" :label="t('organisation.address')">{{ organisation.address }}</Fact>
            <Fact v-if="organisation.contactPerson" :label="t('organisation.contact')">
              <RouterLink :to="path(personPath(organisation.contactPerson))">{{ organisation.contactPerson.name }}</RouterLink>
            </Fact>
          </dl>
        </FactGroup>
      </FactPanel>

      <div class="main">
        <p v-if="!organisation.people.length && !organisation.episodes.length" class="note">{{ t('organisation.nothing') }}</p>

        <section v-for="list in workLists" :key="list.id" :aria-labelledby="list.id">
          <div class="heading">
            <h2 :id="list.id">{{ list.title }}</h2>
            <span class="count">{{ list.works.length }}</span>
          </div>
          <ul class="people">
            <li v-for="work in list.works" :key="work.id" class="row">
              <PersonAvatar :image="work.person.avatar" :name="work.person.name" :size="40"/>
              <div>
                <RouterLink class="name" :to="path(personPath(work.person))">{{ work.person.name }}</RouterLink>
                <p v-if="work.title || workPeriod(work, t, dateLocale)" class="line">
                  {{ [work.title, workPeriod(work, t, dateLocale)].filter(Boolean).join(' · ') }}
                </p>
              </div>
            </li>
          </ul>
        </section>

        <section v-if="organisation.episodes.length" aria-labelledby="supported">
          <div class="heading">
            <h2 id="supported">{{ t('organisation.supported') }}</h2>
            <span class="count">{{ organisation.episodes.length }}</span>
          </div>
          <ul class="episodes">
            <EpisodeRow v-for="episode in organisation.episodes" :key="episode.id" :episode="episode" thumbnail/>
          </ul>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

// Its logo beside what it is
.organisation {
  display: flex;
  align-items: center;
  gap: 20px;
}

.people {
  padding: 0;
  list-style: none;
}

.row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}

.name {
  font-weight: 600;
  color: var(--ink);

  &:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}

.line {
  margin-top: 2px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.icons {
  display: flex;
  flex-wrap: wrap;
  margin: calc((20px - var(--icon-link)) / 2) 0 calc((20px - var(--icon-link)) / 2) var(--icon-link-inset);
}

.number {
  font-family: var(--font-mono);
}

dd a {
  @include page.link;
}

@media (max-width: 640px) {
  .organisation {
    gap: 14px;
  }
}
</style>
