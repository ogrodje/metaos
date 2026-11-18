<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { I18nT } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import { keepUnchanged, useLiveRefresh } from '@/changes'
import FilterBar from '@/components/FilterBar.vue'
import LoadError from '@/components/LoadError.vue'
import LoadingRows from '@/components/LoadingRows.vue'
import NoMatch from '@/components/NoMatch.vue'
import OrganisationForm from '@/components/OrganisationForm.vue'
import OrganisationLogo from '@/components/OrganisationLogo.vue'
import PageHeader from '@/components/PageHeader.vue'
import SignInPrompt from '@/components/SignInPrompt.vue'
import { errorMessage, graphql } from '@/graphql'
import { matches } from '@/lists'
import {
  type GooImportSummary, IMPORT_FROM_GOO, type Organisation, ORGANISATION_FIELDS, organisationPath, type OrganisationTier,
  siteName, tierName,
} from '@/organisations'
import { useClearQuery, useQueryState } from '@/query'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/useLocale'

/** An organisation in the list: how many work there now, and how many episodes it supported */
interface Listed extends Organisation {
  people: { id: string, current: boolean }[]
  episodes: { id: string }[]
}

const auth = useAuthStore()
const router = useRouter()
const { t, path } = useLocale()

// Every organisation, by name; undefined while they load
const organisations = ref<Listed[]>()
const error = ref<unknown>()

let loads = 0

// The team's, so they load for staff only; quietly when MetaOS's data changes, as the other lists
async function load(quietly = false) {
  const load = ++loads
  if (!quietly) {
    organisations.value = undefined
    error.value = undefined
  }
  if (!auth.team) return
  try {
    const data = await graphql<{ organisations: Listed[] }>(`{
      organisations { ${ORGANISATION_FIELDS} people { id current } episodes { id } }
    }`)
    if (load !== loads) return
    organisations.value = keepUnchanged(organisations.value, data.organisations)
    error.value = undefined
  } catch (e) {
    if (load !== loads) return
    if (quietly) throw e
    error.value = e
  }
}

watch(() => auth.team, () => void load(), { immediate: true })
useLiveRefresh(() => load(true))

const find = useQueryState('q', '')
const clear = useClearQuery()

const shown = computed(() => (organisations.value ?? []).filter((organisation) =>
  matches(`${organisation.name} ${organisation.description ?? ''} ${organisation.websiteUrl ?? ''}`, find.value)))

// Supporters, partners, then the rest, e.g. the companies people work at; groups without any left out. Archived ones
// at the end, folded.
const GROUPS: OrganisationTier[] = ['SUPPORTER', 'PARTNER', 'NONE']
const groups = computed(() => GROUPS
  .map((tier) => ({
    tier,
    organisations: shown.value.filter((organisation) => organisation.tier === tier && !organisation.archived),
  }))
  .filter((group) => group.organisations.length))
const archived = computed(() => shown.value.filter((organisation) => organisation.archived))
const archivedOpen = ref(false)

// The team's: a new organisation, whose page it then opens
const creating = ref(false)

function created(organisation: Organisation) {
  void router.push(path(organisationPath(organisation)))
}

// Importing Goo's meetups as organisations, and its upcoming events, once asked
const importing = ref<'asking' | 'busy'>()
const imported = ref<GooImportSummary>()
const importError = ref<string>()

async function importFromGoo() {
  if (importing.value === 'busy') return
  importing.value = 'busy'
  importError.value = undefined
  try {
    imported.value = (await graphql<{ summary: GooImportSummary }>(IMPORT_FROM_GOO)).summary
    importing.value = undefined
    void load(true).catch(() => undefined)
  } catch (e) {
    importError.value = errorMessage(e, t)
    importing.value = 'asking'
  }
}

// What's said about it in its row: what it is, else its website
function line(organisation: Listed) {
  return organisation.description ?? (organisation.websiteUrl ? siteName(organisation.websiteUrl) : null)
}

// How many work there now, and the episodes it supported
function counts(organisation: Listed) {
  const people = organisation.people.filter((work) => work.current).length
  const episodes = organisation.episodes.length
  return [
    people ? t('organisations.peopleCount', { n: people }, people) : null,
    episodes ? t('organisations.episodeCount', { n: episodes }, episodes) : null,
  ].filter(Boolean).join(' · ')
}
</script>

<template>
  <main class="content">
    <SignInPrompt v-if="auth.ready && !auth.authenticated" :title="t('organisations.signInTitle')">
      <I18nT keypath="organisations.signIn" scope="global">
        <template #usage><RouterLink class="usage" :to="path('/usage')">{{ t('app.usage') }}</RouterLink></template>
      </I18nT>
    </SignInPrompt>

    <template v-else>
      <PageHeader :title="t('pages.organisations')" :count="organisations?.length"
                  :shown="find.trim() && organisations ? shown.length : undefined">
        {{ t('organisations.lede') }}
        <template v-if="auth.team" #actions>
          <button type="button" class="button quiet" :aria-expanded="!!importing" aria-controls="goo-import"
                  @click="importing = importing ? undefined : 'asking'; imported = undefined">{{ t('organisations.goo.import') }}</button>
          <button type="button" class="button" :class="{ primary: !creating }" :aria-expanded="creating" aria-controls="new-organisation"
                  @click="creating = !creating">
            {{ creating ? t('common.cancel') : t('organisations.create') }}
          </button>
        </template>
      </PageHeader>

      <template v-if="auth.team">
        <div v-if="importing" id="goo-import" class="ask" role="group" aria-labelledby="goo-ask" @keydown.esc="importing = undefined">
          <p id="goo-ask">{{ t('organisations.goo.ask') }}</p>
          <span class="buttons">
            <button type="button" class="button small primary" :aria-disabled="importing === 'busy' || undefined" @click="importFromGoo">
              {{ importing === 'busy' ? t('organisations.goo.importing') : t('organisations.goo.confirm') }}
            </button>
            <button type="button" class="button small" :disabled="importing === 'busy'" @click="importing = undefined">{{ t('common.cancel') }}</button>
          </span>
          <p v-if="importError" class="error" role="alert">{{ importError }}</p>
        </div>
        <p v-if="imported" class="done" role="status">
          {{ t('organisations.goo.done', {
            created: t('organisations.goo.created', { n: imported.created }, imported.created),
            updated: t('organisations.goo.updated', { n: imported.updated }, imported.updated),
            events: t('organisations.goo.events', { n: imported.events }, imported.events),
            logos: t('organisations.goo.logos', { n: imported.logos }, imported.logos),
          }) }}
        </p>
        <OrganisationForm v-if="creating" id="new-organisation" @saved="created" @close="creating = false"/>
      </template>

      <!-- Signed in as a member, not staff -->
      <p v-if="auth.ready && !auth.team" class="note">{{ t('organisations.staffOnly') }}</p>

      <template v-else>
        <FilterBar v-model:find="find" :placeholder="t('organisations.find')"/>

        <LoadError v-if="error" :what="t('organisations.what')" :error="error" @retry="load()"/>
        <LoadingRows v-else-if="!organisations" :rows="6" :label="t('organisations.loading')"/>
        <p v-else-if="!organisations.length" class="note">{{ t('organisations.none') }}</p>
        <NoMatch v-else-if="!shown.length" :find="find" @clear="clear(['q'])">{{ t('organisations.noMatch') }}</NoMatch>
        <template v-else>
          <section v-for="group in groups" :key="group.tier" :aria-labelledby="`tier-${group.tier}`">
            <div class="heading">
              <h2 :id="`tier-${group.tier}`">{{ tierName(group.tier, t) }}</h2>
              <span class="count">{{ group.organisations.length }}</span>
            </div>
            <ul class="organisations">
              <li v-for="organisation in group.organisations" :key="organisation.id" class="row">
                <OrganisationLogo :image="organisation.logo" :name="organisation.name" :size="40"/>
                <div class="what">
                  <RouterLink class="name" :to="path(organisationPath(organisation))">{{ organisation.name }}</RouterLink>
                  <p v-if="line(organisation)" class="line">{{ line(organisation) }}</p>
                </div>
                <p class="side">
                  <span v-if="organisation.tier !== 'NONE' && !organisation.supporting" class="ended">{{ t('organisations.notNow') }}</span>
                  <span v-if="organisation.collectsEvents">{{ t('organisations.gathersEvents') }}</span>
                  <span v-if="counts(organisation)">{{ counts(organisation) }}</span>
                </p>
              </li>
            </ul>
          </section>

          <!-- No longer active, e.g. a meetup that stopped: folded at the end -->
          <section v-if="archived.length" aria-labelledby="archived">
            <div class="heading">
              <h2 id="archived">{{ t('organisations.archived') }}</h2>
              <span class="count">{{ archived.length }}</span>
              <button type="button" class="text-button more" :aria-expanded="archivedOpen || !!find.trim()" aria-controls="archived-list"
                      @click="archivedOpen = !archivedOpen">{{ archivedOpen ? t('organisations.fold') : t('common.showAll') }}</button>
            </div>
            <ul v-if="archivedOpen || find.trim()" id="archived-list" class="organisations">
              <li v-for="organisation in archived" :key="organisation.id" class="row archived">
                <OrganisationLogo :image="organisation.logo" :name="organisation.name" :size="40"/>
                <div class="what">
                  <RouterLink class="name" :to="path(organisationPath(organisation))">{{ organisation.name }}</RouterLink>
                  <p v-if="line(organisation)" class="line">{{ line(organisation) }}</p>
                </div>
                <p class="side"><span v-if="counts(organisation)">{{ counts(organisation) }}</span></p>
              </li>
            </ul>
            <p v-else class="note">{{ t('organisations.archivedNote') }}</p>
          </section>
        </template>
      </template>
    </template>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.organisations {
  padding: 0;
  list-style: none;
}

// As the Episodes page's rows: under rules, the logo in a column of its own, what it is, and at the right how many
.row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side {
  display: flex;
  gap: 12px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
  white-space: nowrap;
}

.ended {
  color: var(--warning);
}

.archived :deep(img), .archived .name {
  opacity: .7;
}

// Asked before importing from Goo, under the heading
.ask {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  font-size: var(--text-small);
  line-height: 20px;

  .buttons {
    display: flex;
    gap: 8px;
  }

  .error {
    flex-basis: 100%;
    color: var(--accent-text);
  }
}

.done {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--success);
}

.usage {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}

@media (max-width: 640px) {
  .row {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .side {
    grid-column: 2;
  }
}
</style>
