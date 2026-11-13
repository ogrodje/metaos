<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { I18nT } from 'vue-i18n'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faArrowDown, faCheck, faCode, faKey, faMagnifyingGlass, faRobot } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { useRuntimeConfig } from '#imports'
import CopyCommand from '@/components/CopyCommand.vue'
import MarkedText from '@/components/MarkedText.vue'
import { errorMessage, UserError } from '@/graphql'
import { searchWords } from '@/search'
import { usePageMeta } from '@/seo'
import { useAuthStore } from '@/stores/auth'
import { toolGroups, TOOLS_VERSION } from '@/tools'
import { TIME_ZONE } from '@/i18n'
import { type Client, CLIENTS, clientFrom, curlCommand, filterTools, movedFrom, tabAfter, toolCount, withTimeout } from '@/usage'
import { useLocale } from '@/useLocale'

// API and MCP usage: the two ways into MetaOS, MCP for AI assistants and GraphQL for scripts and apps, and the tools
// an assistant gets

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const id = useId()
const { t, dateLocale } = useLocale()

// Where MCP clients connect, and the site, from the runtime config (nuxt.config.ts), so the server's page says them too
const { mcpUrl, siteUrl } = useRuntimeConfig().public

// Where MCP was before it moved to ogrodje.si (OG-59), and until when that answers, for those who added it there. In
// Slovenian as 30. 11. 2026, as "do" wants the month's genitive (novembra), which Intl doesn't give
const moved = movedFrom(mcpUrl)
const movedUntil = computed(() => moved?.until.toLocaleDateString(dateLocale.value, {
  day: 'numeric', month: dateLocale.value === 'sl-SI' ? 'numeric' : 'long', year: 'numeric', timeZone: TIME_ZONE,
}))

usePageMeta(() => ({ description: t('usage.description') }))

// The assistant whose steps are shown, kept in the address (?client=), as search keeps what's searched
const client = computed(() => clientFrom(route.query.client))
const clientName = computed(() => CLIENTS.find((c) => c.id === client.value)?.name)

function tabId(client: Client) {
  return `${id}-tab-${client}`
}

function choose(chosen: Client) {
  if (chosen !== client.value) void router.replace({ query: { ...route.query, client: chosen }, hash: route.hash })
}

// ← and → go through the tabs, as do Home and End, and show the one they're on
function onTabKeydown(event: KeyboardEvent, index: number) {
  const next = tabAfter(event.key, index, CLIENTS.length)
  const chosen = next === null ? undefined : CLIENTS[next]
  if (!chosen) return
  event.preventDefault()
  choose(chosen.id)
  document.getElementById(tabId(chosen.id))?.focus()
}

// For all of the user's projects, not only the one they happen to be in
const claudeCommand = computed(() => `claude mcp add --transport http --scope user ogrodje ${mcpUrl}`)
// Codex's config.toml is the user's own (~/.codex), so it's in all their projects, and the IDE extension's too
const codexCommand = computed(() => `codex mcp add ogrodje --url ${mcpUrl}`)
const codexLogin = 'codex mcp login ogrodje'

// MetaOS's GraphQL API, on the site's own origin, where the Ingress sends it to MetaOS (GraphQL.kt, schema.graphqls)
const graphqlUrl = `${siteUrl}/graphql`
const SCHEMA_URL = 'https://github.com/ogrodje/metaos/blob/master/src/main/resources/graphql/schema.graphqls'

const publicCommand = curlCommand(graphqlUrl, { query: '{ releasedEpisodes(limit: 3) { code title airedDate youtubeUrl } }' })
const meCommand = curlCommand(graphqlUrl, { query: '{ me { username person { name } } }' }, true)
const commentCommand = curlCommand(graphqlUrl, {
  query: 'mutation ($ref: String!, $body: String!) { commentOnEpisode(ref: $ref, body: $body) { id createdAt } }',
  variables: { ref: 'S05E08', body: 'Hello from the API' },
}, true)

// The signed-in user's own token, from their sign-in here (metaos-ui, with metaos:read and metaos:write): copied, or
// shown to copy by hand when the browser won't let the page write to the clipboard
const tokenCopied = ref(false)
const shownToken = ref<string>()
const tokenError = ref<string>()
const tokenField = ref<HTMLTextAreaElement>()
let tokenTimer: ReturnType<typeof setTimeout> | undefined

// Written while the click still counts, which Safari needs, with the token to come: it's refreshed first when it's
// about to expire
async function writeToClipboard(text: Promise<string>) {
  if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    const blob = text.then((value) => new Blob([value], { type: 'text/plain' }))
    await navigator.clipboard.write([new ClipboardItem({ 'text/plain': blob })])
  } else {
    await navigator.clipboard.writeText(await text)
  }
}

async function copyToken() {
  tokenError.value = undefined
  clearTimeout(tokenTimer)
  const pending = auth.token().then((token) => {
    if (!token) throw new UserError('usage.token.signedOut')
    return token
  })
  try {
    await withTimeout(writeToClipboard(pending))
    shownToken.value = undefined
    tokenCopied.value = true
    tokenTimer = setTimeout(() => (tokenCopied.value = false), 2000)
  } catch {
    try {
      shownToken.value = await pending
      await nextTick()
      tokenField.value?.select()
    } catch (error) {
      tokenError.value = errorMessage(error, t)
    }
  }
}

// The tools, in the page's language, narrowed as a filter's typed, with the words it found marked
const filter = ref('')
const filterWords = computed(() => searchWords(filter.value))
const groups = computed(() => toolGroups(t))
const shownGroups = computed(() => filterTools(groups.value, filter.value))
const total = computed(() => toolCount(groups.value))
const shownCount = computed(() => toolCount(shownGroups.value))
const matches = computed(() => {
  if (!filterWords.value.length) return ''
  return shownCount.value
    ? t('usage.tools.shown', { shown: shownCount.value, total: total.value }, total.value)
    : t('usage.tools.noMatch', { filter: filter.value.trim() })
})
</script>

<template>
  <main class="content">
    <header class="top">
      <div class="title">
        <h1>{{ t('pages.usage') }}</h1>
        <p class="subtitle">{{ t('usage.subtitle') }}</p>
      </div>

      <!-- The two ways in, each leading down to its section -->
      <div class="ways">
        <div class="way">
          <span class="way-icon" aria-hidden="true"><FontAwesomeIcon :icon="faRobot"/></span>
          <p class="way-name">MCP</p>
          <p class="way-for">{{ t('usage.ways.mcp') }}</p>
          <p class="way-meta">{{ t('usage.ways.tools', { n: total }, total) }}</p>
          <a class="way-link" href="#mcp">{{ t('usage.ways.connect') }}<FontAwesomeIcon :icon="faArrowDown"/></a>
        </div>
        <div class="way">
          <span class="way-icon" aria-hidden="true"><FontAwesomeIcon :icon="faCode"/></span>
          <p class="way-name">GraphQL</p>
          <p class="way-for">{{ t('usage.ways.graphql') }}</p>
          <p class="way-meta"><code>POST /graphql</code></p>
          <a class="way-link" href="#graphql">{{ t('usage.ways.ask') }}<FontAwesomeIcon :icon="faArrowDown"/></a>
        </div>
      </div>
    </header>

    <section aria-labelledby="mcp">
      <div class="heading">
        <h2 id="mcp">MCP</h2>
      </div>
      <p class="intro">{{ t('usage.mcp.intro') }}</p>
      <I18nT v-if="moved" keypath="usage.mcp.moved" tag="p" class="moved" scope="global">
        <template #url><code>{{ mcpUrl }}</code></template>
        <template #old><code>{{ moved.from }}</code></template>
        <template #date>{{ movedUntil }}</template>
      </I18nT>

      <div class="tabs" role="tablist" :aria-label="t('usage.mcp.assistant')">
        <button v-for="(c, index) in CLIENTS" :id="tabId(c.id)" :key="c.id" type="button" role="tab"
                :aria-selected="client === c.id" :aria-controls="`${id}-panel`" :tabindex="client === c.id ? 0 : -1"
                @click="choose(c.id)" @keydown="onTabKeydown($event, index)">
          {{ c.name }}
        </button>
      </div>

      <div :id="`${id}-panel`" class="panel" role="tabpanel" :aria-labelledby="tabId(client)" tabindex="0">
        <!-- A sequence, on a rail as the history's -->
        <ol class="steps">
            <li class="step">
              <span class="marker" aria-hidden="true">1</span>
              <div>
                <p class="step-title">{{ t('usage.mcp.add', { client: clientName }) }}</p>
                <template v-if="client === 'claude-code'">
                  <I18nT keypath="usage.claudeCode.add" tag="p" class="step-details" scope="global">
                    <template #scope><code>--scope user</code></template>
                  </I18nT>
                  <CopyCommand :command="claudeCommand"/>
                </template>
                <I18nT v-else-if="client === 'claude'" keypath="usage.claude.add" tag="p" class="step-details" scope="global">
                  <template #connectors><strong>Customize → Connectors</strong></template>
                  <template #add><strong>Add → Add custom connector</strong></template>
                  <template #name><strong>Ogrodje</strong></template>
                  <template #url><code>{{ mcpUrl }}</code></template>
                </I18nT>
                <template v-else-if="client === 'codex'">
                  <I18nT keypath="usage.codex.add" tag="p" class="step-details" scope="global">
                    <template #config><code>~/.codex/config.toml</code></template>
                  </I18nT>
                  <CopyCommand :command="codexCommand"/>
                </template>
                <I18nT v-else keypath="usage.chatgpt.add" tag="p" class="step-details" scope="global">
                  <template #plugins><strong>Plugins</strong></template>
                  <template #add><strong>Add → Add custom MCP server</strong></template>
                  <template #name><strong>Ogrodje</strong></template>
                  <template #url><code>{{ mcpUrl }}</code></template>
                  <template #oauth><strong>OAuth</strong></template>
                  <template #dcr><strong>Dynamic Client Registration (DCR)</strong></template>
                  <template #developer><strong>Developer mode</strong></template>
                  <template #settings><strong>Settings → Apps → Advanced settings</strong></template>
                  <template #apps><strong>Apps</strong></template>
                </I18nT>
              </div>
            </li>
            <li class="step">
              <span class="marker" aria-hidden="true">2</span>
              <div>
                <p class="step-title">{{ t('usage.mcp.signIn') }}</p>
                <I18nT v-if="client === 'claude-code'" keypath="usage.claudeCode.signIn" tag="p" class="step-details" scope="global">
                  <template #mcp><code>/mcp</code></template>
                  <template #ogrodje><strong>ogrodje</strong></template>
                </I18nT>
                <I18nT v-else-if="client === 'claude'" keypath="usage.claude.signIn" tag="p" class="step-details" scope="global">
                  <template #connect><strong>Connect</strong></template>
                  <template #plus><strong>+</strong></template>
                  <template #connectors><strong>Connectors</strong></template>
                </I18nT>
                <template v-else-if="client === 'codex'">
                  <p class="step-details">{{ t('usage.codex.signIn') }}</p>
                  <CopyCommand :command="codexLogin"/>
                </template>
                <I18nT v-else keypath="usage.chatgpt.signIn" tag="p" class="step-details" scope="global">
                  <template #at><strong>@</strong></template>
                  <template #name><strong>Ogrodje</strong></template>
                </I18nT>
              </div>
            </li>

            <li class="step">
              <span class="marker" aria-hidden="true">3</span>
              <div>
                <p class="step-title">{{ t('usage.mcp.team') }}</p>
                <p class="step-details">{{ t('usage.mcp.teamDetails') }}</p>
              </div>
            </li>
            <li class="step">
              <span class="marker" aria-hidden="true">4</span>
              <div>
                <p class="step-title">{{ t('usage.mcp.ask') }}</p>
                <p class="step-details">{{ t('usage.mcp.askDetails') }}</p>
              </div>
            </li>
            <!-- The address MetaOS gives an assistant whose tools are older than its own (McpVersion.kt) -->
            <li id="refresh" class="step">
              <span class="marker" aria-hidden="true">5</span>
              <div>
                <p class="step-title">{{ t('usage.refresh.title') }}</p>
                <p class="step-details">
                  <I18nT keypath="usage.refresh.details" scope="global">
                    <template #version><code>{{ TOOLS_VERSION }}</code></template>
                  </I18nT>
                  {{ ' ' }}
                  <I18nT v-if="client === 'claude-code'" keypath="usage.refresh.claudeCode" scope="global">
                    <template #mcp><code>/mcp</code></template>
                    <template #ogrodje><strong>ogrodje</strong></template>
                  </I18nT>
                  <I18nT v-else-if="client === 'claude'" keypath="usage.refresh.claude" scope="global">
                    <template #connectors><strong>Customize → Connectors</strong></template>
                    <template #name><strong>Ogrodje</strong></template>
                    <template #refresh><strong>… → Refresh tools list</strong></template>
                  </I18nT>
                  <template v-else-if="client === 'codex'">{{ t('usage.refresh.codex') }}</template>
                  <I18nT v-else keypath="usage.refresh.chatgpt" scope="global">
                    <template #apps><strong>Apps</strong></template>
                  </I18nT>
                </p>
              </div>
            </li>
        </ol>
      </div>

      <I18nT keypath="usage.mcp.otherClients" tag="p" class="note" scope="global">
        <template #url><code>{{ mcpUrl }}</code></template>
      </I18nT>
    </section>

    <section aria-labelledby="graphql">
      <div class="heading">
        <h2 id="graphql">GraphQL</h2>
      </div>
      <p class="intro">{{ t('usage.graphql.intro') }}</p>

      <dl class="details">
        <div>
          <dt>{{ t('usage.graphql.endpoint') }}</dt>
          <I18nT keypath="usage.graphql.endpointDetails" tag="dd" scope="global">
            <template #endpoint><code>POST {{ graphqlUrl }}</code></template>
            <template #query><code>query</code></template>
            <template #variables><code>variables</code></template>
          </I18nT>
        </div>
        <div>
          <dt>{{ t('usage.graphql.errors') }}</dt>
          <dd>{{ t('usage.graphql.errorsDetails') }}</dd>
        </div>
        <div>
          <dt>{{ t('usage.graphql.schema') }}</dt>
          <I18nT keypath="usage.graphql.schemaDetails" tag="dd" scope="global">
            <template #schema><a class="link" :href="SCHEMA_URL">schema.graphqls</a></template>
          </I18nT>
        </div>
        <div>
          <dt>{{ t('usage.graphql.limits') }}</dt>
          <dd>{{ t('usage.graphql.limitsDetails') }}</dd>
        </div>
      </dl>

      <!-- Each case: what it is on the left, an example on the right -->
      <div class="case">
        <div class="explain">
          <h3>{{ t('usage.graphql.public') }}</h3>
          <I18nT keypath="usage.graphql.publicDetails" tag="p" scope="global">
            <template #episodes><code>episodes</code></template>
            <template #people><code>people</code></template>
          </I18nT>
        </div>
        <div class="examples">
          <CopyCommand :label="t('usage.graphql.publicExample')" :command="publicCommand"/>
        </div>
      </div>

      <div class="case">
        <div class="explain">
          <h3>{{ t('usage.graphql.withToken') }}</h3>
          <I18nT keypath="usage.graphql.withTokenDetails" tag="p" scope="global">
            <template #read><code>metaos:read</code></template>
            <template #write><code>metaos:write</code></template>
          </I18nT>

          <div class="token-card">
            <p class="token-head">
              <FontAwesomeIcon :icon="faKey"/>
              <b>{{ t('usage.token.title') }}</b>
              <span class="lasts">{{ t('usage.token.lasts') }}</span>
            </p>
            <template v-if="auth.authenticated">
              <I18nT keypath="usage.token.keep" tag="p" scope="global">
                <template #token><code>TOKEN</code></template>
              </I18nT>
              <div class="token-actions">
                <button type="button" class="button" @click="copyToken">
                  <FontAwesomeIcon :icon="tokenCopied ? faCheck : faCopy"/>{{ tokenCopied ? t('usage.token.copied') : t('usage.token.copy') }}
                </button>
                <span class="token-status" role="status">{{ tokenCopied ? t('usage.token.copiedStatus') : '' }}</span>
              </div>
              <template v-if="shownToken">
                <p>{{ t('usage.token.notCopied') }}</p>
                <textarea ref="tokenField" class="token-field" readonly rows="4" spellcheck="false"
                          :aria-label="t('usage.token.title')" :value="shownToken" @focus="tokenField?.select()"></textarea>
              </template>
              <p v-if="tokenError" class="error" role="alert">{{ t('usage.token.none', { error: tokenError }) }}</p>
            </template>
            <p v-else>{{ t('usage.token.signIn') }}</p>
          </div>
        </div>
        <div class="examples">
          <CopyCommand :label="t('usage.graphql.meExample')" :command="meCommand"/>
          <CopyCommand :label="t('usage.graphql.commentExample')" :command="commentCommand"/>
        </div>
      </div>
    </section>

    <section aria-labelledby="tools">
      <div class="heading">
        <h2 id="tools">{{ t('usage.tools.title') }}</h2>
        <span class="count">{{ total }}</span>
      </div>
      <I18nT keypath="usage.tools.intro" tag="p" class="intro" scope="global">
        <template #version><code>{{ TOOLS_VERSION }}</code></template>
      </I18nT>

      <div class="filter">
        <FontAwesomeIcon class="glass" :icon="faMagnifyingGlass"/>
        <input v-model="filter" type="search" :aria-label="t('usage.tools.filter')" :placeholder="t('usage.tools.filterPlaceholder')"
               autocomplete="off" spellcheck="false" :aria-describedby="`${id}-matches`"/>
      </div>
      <p :id="`${id}-matches`" class="matches" :class="{ none: filterWords.length && !shownCount }" role="status">{{ matches }}</p>

      <div class="groups">
        <section v-for="group in shownGroups" :key="group.id" class="group" :aria-labelledby="`${id}-${group.id}`">
          <h3 :id="`${id}-${group.id}`" class="group-title">
            {{ group.title }}
            <span class="about">{{ group.about }}</span>
          </h3>
          <dl class="tools">
            <div v-for="tool in group.tools" :key="tool.name" class="tool">
              <dt><code><MarkedText :text="tool.name" :words="filterWords"/></code></dt>
              <dd><MarkedText :text="tool.description" :words="filterWords"/></dd>
            </div>
          </dl>
        </section>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
// The other pages' shell, header, headings and label and value rows
@use '../assets/page';

h1, h2, h3 {
  text-wrap: balance;
}

code {
  font-family: var(--font-mono);
  font-size: var(--text-small);
}

// Inline, in text
p code, dd code {
  padding: 1px 5px;
  background: var(--hover);
  border-radius: var(--radius-xs);
  overflow-wrap: anywhere;
}

strong {
  font-weight: 600;
  color: var(--ink);
}

.link {
  color: var(--accent-text);

  &:hover {
    text-decoration: underline;
  }
}

// A section's link lands it a little below the top, as does a step's
section, .step {
  scroll-margin-top: 24px;
}

.intro {
  max-width: 68ch;
  padding-bottom: 16px;
  font-size: var(--text-body);
  line-height: 22px;
  color: var(--secondary);
}

// That MCP moved (OG-59): a line to act on, so it stands apart from the intro, quietly
.moved {
  max-width: 68ch;
  margin-bottom: 16px;
  padding: 10px 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
  background: var(--surface);
  font-size: var(--text-body);
  line-height: 22px;
}

// The page's title, and the two ways in under it
.top {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ways {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

// A quiet card, all of it its link
.way {
  position: relative;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  column-gap: 14px;
  align-content: start;
  padding: 20px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-lg);

  &:hover {
    border-color: var(--hairline-strong);
  }

  &:has(.way-link:focus-visible) {
    outline: 2px solid var(--accent-text);
    outline-offset: 2px;
  }

  > :not(.way-icon) {
    grid-column: 2;
  }
}

.way-icon {
  grid-row: 1 / span 4;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  font-size: var(--text-section);
  color: var(--ink);
  background: var(--hover);
  border-radius: var(--radius);
}

.way-name {
  font-size: var(--text-lead);
  line-height: 22px;
  font-weight: 600;
}

.way-for {
  margin-top: 2px;
  color: var(--secondary);
}

.way-meta {
  margin-top: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);

  code {
    font-size: var(--text-caption);
  }
}

.way-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: var(--text-small);
  line-height: 20px;
  font-weight: 500;
  color: var(--accent-text);

  svg {
    font-size: var(--text-micro);
  }

  // The whole card
  &::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: var(--radius-lg);
  }

  &:focus-visible {
    outline: 0;
  }
}

// Which assistant the steps are for: a segmented control, the chosen one raised
.tabs {
  display: inline-flex;
  max-width: 100%;
  overflow-x: auto;
  gap: 2px;
  padding: 3px;
  margin-bottom: 24px;
  background: var(--hover);
  border-radius: var(--radius);
  scrollbar-width: none;

  button {
    flex: none;
    padding: 6px 14px;
    font: inherit;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    cursor: pointer;

    &:hover {
      color: var(--ink);
    }

    &[aria-selected='true'] {
      font-weight: 500;
      color: var(--ink);
      background: #FFFFFF;
      box-shadow: 0 0 0 1px var(--hairline), 0 1px 2px rgba(0, 0, 0, .06);
    }

    &:focus-visible {
      outline-offset: 0;
    }
  }
}

.panel {
  border-radius: var(--radius);
}

// The steps on a rail through their numbers, as the history's dates
.steps {
  padding: 0;
  list-style: none;
}

.step {
  position: relative;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  column-gap: 16px;
  padding-bottom: 28px;

  &::before {
    content: '';
    position: absolute;
    left: 14px;
    top: 32px;
    bottom: 4px;
    width: 1px;
    background: var(--hairline);
  }

  &:last-child {
    padding-bottom: 0;

    &::before {
      display: none;
    }
  }

  > div {
    min-width: 0;
  }
}

.marker {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  color: var(--secondary);
  background: #FFFFFF;
  border: 1px solid var(--hairline-strong);
  border-radius: 50%;
}

// Where it gets you
.step:last-child .marker {
  color: var(--accent-text);
  border-color: var(--accent);
}

.step-title {
  font-size: var(--text-lead);
  line-height: 28px;
  font-weight: 500;
}

.step-details {
  margin-top: 2px;
  max-width: 640px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
}

.note {
  margin-top: 28px;
  padding-top: 14px;
  border-top: 1px solid var(--rule);

  code {
    color: var(--secondary);
    word-break: break-all;
  }
}

// GraphQL's facts, as the other pages' details, then each case: what it is beside an example
.details {
  margin-bottom: 8px;
}

.case {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: 16px 32px;
  padding: 24px 0;
  border-top: 1px solid var(--rule);

  h3 {
    font-size: var(--text-body);
    line-height: 22px;
    font-weight: 600;
  }

  .explain > p {
    margin-top: 4px;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
  }
}

// Each its own block, the first in line with the case's title
.examples > :first-child {
  margin-top: 0;
}

// Your token, in a card of its own
.token-card {
  margin-top: 16px;
  padding: 14px 16px 16px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);

  > p {
    margin-top: 6px;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
  }
}

.token-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  margin: 0;

  svg {
    width: 14px;
    color: var(--muted);
  }

  b {
    font-size: var(--text-body);
    font-weight: 600;
    color: var(--ink);
  }
}

.token-card > .token-head {
  margin-top: 0;
}

.lasts {
  padding: 0 8px;
  font-size: var(--text-micro);
  line-height: 20px;
  color: var(--secondary);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
}

.token-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-top: 12px;

  .button svg {
    width: 14px;
    color: var(--secondary);
  }
}

.token-status {
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);
}

// A token to copy by hand: all of it, selected when it's focused
.token-field {
  display: block;
  width: 100%;
  margin-top: 8px;
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--ink);
  word-break: break-all;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  resize: vertical;
}

.error {
  margin-top: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--accent-text);
}

// The tools' filter, and what it found
.filter {
  position: relative;
  max-width: 420px;

  .glass {
    position: absolute;
    left: 14px;
    top: 50%;
    width: 14px;
    color: var(--muted);
    transform: translateY(-50%);
    pointer-events: none;
  }

  input {
    width: 100%;
    min-height: 44px;
    padding: 0 12px 0 38px;
    font: inherit;
    color: var(--ink);
    background: #FFFFFF;
    border: 1px solid var(--hairline);
    border-radius: var(--radius);

    &::placeholder {
      color: var(--muted);
    }

    &:hover {
      border-color: var(--hairline-strong);
    }

    &:focus-visible {
      outline-offset: -1px;
      border-color: transparent;
    }
  }
}

.matches {
  min-height: 20px;
  margin: 8px 0 4px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--muted);

  &.none {
    color: var(--secondary);
  }
}

// Two columns of groups, each whole in one
.groups {
  columns: 2;
  column-gap: 40px;
}

.group {
  break-inside: avoid;
  padding-top: 16px;
}

.group-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: 8px;
  padding-bottom: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  font-weight: 600;
  border-bottom: 1px solid var(--rule);

  .about {
    font-weight: 400;
    color: var(--secondary);
  }
}

.tools {
  margin: 0;
}

.tool {
  padding: 8px 0;
  border-bottom: 1px solid var(--rule);

  dt code {
    font-size: var(--text-caption);
    line-height: 20px;
    font-weight: 500;
    color: var(--ink);
    overflow-wrap: anywhere;
  }

  dd {
    margin: 2px 0 0;
    font-size: var(--text-small);
    line-height: 20px;
    color: var(--secondary);
  }
}

@media (prefers-reduced-motion: no-preference) {
  .way, .tabs button, .filter input {
    transition: border-color .15s, background-color .15s, color .15s;
  }
}

// On phones the ways in, each case and the groups go one under the other
@media (max-width: 640px) {
  .ways, .case {
    grid-template-columns: minmax(0, 1fr);
  }

  .way {
    padding: 16px;
  }

  .tabs button {
    padding: 6px 10px;
  }

  .step {
    column-gap: 12px;
  }

  .groups {
    columns: 1;
  }
}

</style>
