<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faArrowDown, faCheck, faCode, faKey, faMagnifyingGlass, faRobot } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import CopyCommand from '@/components/CopyCommand.vue'
import MarkedText from '@/components/MarkedText.vue'
import { errorMessage } from '@/graphql'
import { searchWords } from '@/search'
import { useAuthStore } from '@/stores/auth'
import { TOOL_GROUPS } from '@/tools'
import { type Client, CLIENTS, clientFrom, curlCommand, filterTools, tabAfter, toolCount, withTimeout } from '@/usage'

// API and MCP usage: the two ways into MetaOS, MCP for AI assistants and GraphQL for scripts and apps, and the tools
// an assistant gets

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const id = useId()

// Where MCP clients connect, from /config.json (Frontend.kt): this page's own origin until it answers, which is right
// wherever MetaOS serves the front-end itself, but not on Vite's dev server
const mcpUrl = ref(`${location.origin}/mcp`)

onMounted(async () => {
  try {
    const config: { mcpUrl?: string } = await (await fetch('/config.json')).json()
    if (config.mcpUrl) mcpUrl.value = config.mcpUrl
  } catch (error) {
    console.error('MetaOS did not say where MCP clients connect', error)
  }
})

// The assistant whose steps are shown, kept in the address (?client=), as search keeps what's searched
const client = computed(() => clientFrom(route.query.client))

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
const claudeCommand = computed(() => `claude mcp add --transport http --scope user ogrodje ${mcpUrl.value}`)
// Codex's config.toml is the user's own (~/.codex), so it's in all their projects, and the IDE extension's too
const codexCommand = computed(() => `codex mcp add ogrodje --url ${mcpUrl.value}`)
const codexLogin = 'codex mcp login ogrodje'

// MetaOS's GraphQL API, on this page's own origin, as MetaOS serves both (GraphQL.kt, schema.graphqls)
const graphqlUrl = `${location.origin}/graphql`
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
    if (!token) throw new Error("You're no longer signed in: sign in again to get one.")
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
      tokenError.value = errorMessage(error)
    }
  }
}

// The tools, narrowed as a filter's typed, with the words it found marked
const filter = ref('')
const filterWords = computed(() => searchWords(filter.value))
const shownGroups = computed(() => filterTools(TOOL_GROUPS, filter.value))
const total = toolCount(TOOL_GROUPS)
const shownCount = computed(() => toolCount(shownGroups.value))
const matches = computed(() => {
  if (!filterWords.value.length) return ''
  return shownCount.value ? `${shownCount.value} of ${total} tools` : `No tools match “${filter.value.trim()}”.`
})
</script>

<template>
  <main class="content">
    <header class="top">
      <div class="title">
        <h1>API and MCP usage</h1>
        <p class="subtitle">Use MetaOS from your AI assistant over MCP, or from your own code over GraphQL.</p>
      </div>

      <!-- The two ways in, each leading down to its section -->
      <div class="ways">
        <div class="way">
          <span class="way-icon" aria-hidden="true"><FontAwesomeIcon :icon="faRobot"/></span>
          <p class="way-name">MCP</p>
          <p class="way-for">For AI assistants: Claude Code, Claude, Codex, ChatGPT</p>
          <p class="way-meta">{{ total }} tools</p>
          <a class="way-link" href="#mcp">Connect an assistant<FontAwesomeIcon :icon="faArrowDown"/></a>
        </div>
        <div class="way">
          <span class="way-icon" aria-hidden="true"><FontAwesomeIcon :icon="faCode"/></span>
          <p class="way-name">GraphQL</p>
          <p class="way-for">For scripts and apps</p>
          <p class="way-meta"><code>POST /graphql</code></p>
          <a class="way-link" href="#graphql">Ask the API<FontAwesomeIcon :icon="faArrowDown"/></a>
        </div>
      </div>
    </header>

    <section aria-labelledby="mcp">
      <div class="heading">
        <h2 id="mcp">MCP</h2>
      </div>
      <p class="intro">
        MetaOS is an MCP server. Connect an AI assistant such as Claude, Codex or ChatGPT to it, and the assistant
        can look up and manage Ogrodje's episodes, people, recording sessions and shows, its Google Docs and its files
        in Koofr, as you.
      </p>

      <div class="tabs" role="tablist" aria-label="Assistant">
        <button v-for="(c, index) in CLIENTS" :id="tabId(c.id)" :key="c.id" type="button" role="tab"
                :aria-selected="client === c.id" :aria-controls="`${id}-panel`" :tabindex="client === c.id ? 0 : -1"
                @click="choose(c.id)" @keydown="onTabKeydown($event, index)">
          {{ c.name }}
        </button>
      </div>

      <div :id="`${id}-panel`" class="panel" role="tabpanel" :aria-labelledby="tabId(client)" tabindex="0">
        <!-- A sequence, on a rail as the history's -->
        <ol class="steps">
            <template v-if="client === 'claude-code'">
              <li class="step">
                <span class="marker" aria-hidden="true">1</span>
                <div>
                  <p class="step-title">Add MetaOS to Claude Code</p>
                  <p class="step-details">In a terminal; <code>--scope user</code> makes it available in all your projects.</p>
                  <CopyCommand :command="claudeCommand"/>
                </div>
              </li>
              <li class="step">
                <span class="marker" aria-hidden="true">2</span>
                <div>
                  <p class="step-title">Sign in</p>
                  <p class="step-details">
                    In Claude Code, run <code>/mcp</code>, pick <strong>ogrodje</strong> and choose Authenticate. Your
                    browser opens MetaOS's sign-in, where you sign in with Discord or Google.
                  </p>
                </div>
              </li>
            </template>

            <template v-else-if="client === 'claude'">
              <li class="step">
                <span class="marker" aria-hidden="true">1</span>
                <div>
                  <p class="step-title">Add MetaOS to Claude</p>
                  <p class="step-details">
                    On claude.ai or in Claude's desktop app, in <strong>Settings → Connectors</strong>, choose <strong>Add
                    custom connector</strong>. Name it <strong>Ogrodje</strong>, with <code>{{ mcpUrl }}</code> as its URL,
                    and leave the advanced settings empty. In a Team or Enterprise organization, an owner adds it for
                    everyone in the organization's settings, and you connect it in yours.
                  </p>
                </div>
              </li>
              <li class="step">
                <span class="marker" aria-hidden="true">2</span>
                <div>
                  <p class="step-title">Sign in</p>
                  <p class="step-details">
                    Choose <strong>Connect</strong> next to it: MetaOS's sign-in opens, where you sign in with Discord or
                    Google. Claude's phone apps use it too, once it's connected. In a chat, turn it on in the
                    <strong>+</strong> menu, under <strong>Connectors</strong>.
                  </p>
                </div>
              </li>
            </template>

            <template v-else-if="client === 'codex'">
              <li class="step">
                <span class="marker" aria-hidden="true">1</span>
                <div>
                  <p class="step-title">Add MetaOS to Codex</p>
                  <p class="step-details">
                    In a terminal. It goes into your own <code>~/.codex/config.toml</code>, so it's in all your projects, in
                    the CLI, Codex's IDE extension and its app.
                  </p>
                  <CopyCommand :command="codexCommand"/>
                </div>
              </li>
              <li class="step">
                <span class="marker" aria-hidden="true">2</span>
                <div>
                  <p class="step-title">Sign in</p>
                  <p class="step-details">
                    Adding it opens MetaOS's sign-in in your browser, where you sign in with Discord or Google. Codex keeps
                    the sign-in and refreshes it; to sign in again, e.g. once you're approved:
                  </p>
                  <CopyCommand :command="codexLogin"/>
                </div>
              </li>
            </template>

            <template v-else>
              <li class="step">
                <span class="marker" aria-hidden="true">1</span>
                <div>
                  <p class="step-title">Add MetaOS to ChatGPT</p>
                  <p class="step-details">
                    On chatgpt.com, in a browser: ChatGPT's apps can't add MCP servers. Under <strong>Plugins</strong>,
                    choose <strong>Add → Add custom MCP server</strong>. Name it <strong>Ogrodje</strong>, with
                    <code>{{ mcpUrl }}</code> as its URL and <strong>OAuth</strong> as its authentication. In the OAuth
                    settings, keep <strong>Dynamic Client Registration (DCR)</strong>; that CIMD is unavailable is expected.
                    Where there's no Plugins, turn on <strong>Developer mode</strong> in <strong>Settings → Apps → Advanced
                    settings</strong> and create it in <strong>Apps</strong>. Which plans may add one is up to OpenAI.
                  </p>
                </div>
              </li>
              <li class="step">
                <span class="marker" aria-hidden="true">2</span>
                <div>
                  <p class="step-title">Sign in</p>
                  <p class="step-details">
                    Creating it opens MetaOS's sign-in, where you sign in with Discord or Google and allow ChatGPT access.
                    In a new chat, type <strong>@</strong> and pick <strong>Ogrodje</strong> for ChatGPT to use it.
                  </p>
                </div>
              </li>
            </template>

            <li class="step">
              <span class="marker" aria-hidden="true">3</span>
              <div>
                <p class="step-title">Get approved</p>
                <p class="step-details">
                  The first time you sign in, an Ogrodje admin has to approve your account; until then MetaOS turns the
                  sign-in down. Once you're approved, sign in again.
                </p>
              </div>
            </li>
            <li class="step">
              <span class="marker" aria-hidden="true">4</span>
              <div>
                <p class="step-title">Ask</p>
                <p class="step-details">
                  For example: “Which episodes are in production?” or “Who's on the next recording day?” The assistant picks
                  the tools it needs from the ones below and, unless you've allowed them, asks you before it uses one.
                </p>
              </div>
            </li>
        </ol>
      </div>

      <p class="note">
        Other MCP clients that sign in with OAuth, such as MCP Inspector, connect to <code>{{ mcpUrl }}</code>.
      </p>
    </section>

    <section aria-labelledby="graphql">
      <div class="heading">
        <h2 id="graphql">GraphQL</h2>
      </div>
      <p class="intro">
        MetaOS's pages get what they show from its GraphQL API, and your scripts and apps can ask it too.
      </p>

      <dl class="details">
        <div>
          <dt>Endpoint</dt>
          <dd><code>POST {{ graphqlUrl }}</code>, with JSON: a <code>query</code> and its <code>variables</code></dd>
        </div>
        <div>
          <dt>Errors</dt>
          <dd>In the answer's body, with a 200</dd>
        </div>
        <div>
          <dt>Schema</dt>
          <dd>
            By introspection, for GraphQL clients, and described field by field in
            <a class="link" :href="SCHEMA_URL">schema.graphqls</a> on GitHub
          </dd>
        </div>
        <div>
          <dt>Limits</dt>
          <dd>15 levels deep and 300 fields a query</dd>
        </div>
      </dl>

      <!-- Each case: what it is on the left, an example on the right -->
      <div class="case">
        <div class="explain">
          <h3>Without a token</h3>
          <p>
            What's public works for anyone: the released episodes, and the people on them. That's also all that
            <code>episodes</code> and <code>people</code> give anyone without a token.
          </p>
        </div>
        <div class="examples">
          <CopyCommand label="The latest three releases" :command="publicCommand"/>
        </div>
      </div>

      <div class="case">
        <div class="explain">
          <h3>With a token</h3>
          <p>
            The rest is the team's. It takes an approved user's token, with <code>metaos:read</code>, and
            <code>metaos:write</code> for changes. Scripts have no sign-in of their own yet, so it's one from MetaOS's
            sign-in, which has both.
          </p>

          <div class="token-card">
            <p class="token-head">
              <FontAwesomeIcon :icon="faKey"/>
              <b>Your token</b>
              <span class="lasts">lasts 5 minutes</span>
            </p>
            <template v-if="auth.authenticated">
              <p>
                It's you to MetaOS, so keep it to yourself. Put it in <code>TOKEN</code> for the commands here, and copy
                a new one once it's run out.
              </p>
              <div class="token-actions">
                <button type="button" class="button" @click="copyToken">
                  <FontAwesomeIcon :icon="tokenCopied ? faCheck : faCopy"/>{{ tokenCopied ? 'Copied' : 'Copy a token' }}
                </button>
                <span class="token-status" role="status">{{ tokenCopied ? 'Copied; it lasts 5 minutes.' : '' }}</span>
              </div>
              <template v-if="shownToken">
                <p>The browser didn't let the page copy it, so here it is to copy yourself:</p>
                <textarea ref="tokenField" class="token-field" readonly rows="4" spellcheck="false"
                          aria-label="Your token" :value="shownToken" @focus="tokenField?.select()"></textarea>
              </template>
              <p v-if="tokenError" class="error" role="alert">There's no token: {{ tokenError }}</p>
            </template>
            <p v-else>Sign in, at the top right, and this is where you copy yours.</p>
          </div>
        </div>
        <div class="examples">
          <CopyCommand label="Who MetaOS takes you for" :command="meCommand"/>
          <CopyCommand label="A comment on S05E08, its text in variables so nothing needs escaping" :command="commentCommand"/>
        </div>
      </div>
    </section>

    <section aria-labelledby="tools">
      <div class="heading">
        <h2 id="tools">Tools</h2>
        <span class="count">{{ total }}</span>
      </div>
      <p class="intro">
        What an assistant can do over MCP, as you. It picks the ones it needs, and asks you first unless you've allowed
        them.
      </p>

      <div class="filter">
        <FontAwesomeIcon class="glass" :icon="faMagnifyingGlass"/>
        <input v-model="filter" type="search" aria-label="Filter the tools" placeholder="Filter by name or what it does"
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
  font-size: 13px;
}

// Inline, in text
p code, dd code {
  padding: 1px 5px;
  background: var(--hover);
  border-radius: 4px;
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

a:focus-visible, button:focus-visible, input:focus-visible, textarea:focus-visible, .panel:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

// A section's link lands it a little below the top
section {
  scroll-margin-top: 24px;
}

.intro {
  max-width: 640px;
  padding-bottom: 16px;
  font-size: 14px;
  line-height: 22px;
  color: var(--secondary);
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
  border-radius: 12px;

  &:hover {
    border-color: #A3A3A3;
  }

  &:has(.way-link:focus-visible) {
    outline: 2px solid var(--accent);
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
  font-size: 17px;
  color: var(--ink);
  background: var(--hover);
  border-radius: 10px;
}

.way-name {
  font-size: 15px;
  line-height: 22px;
  font-weight: 600;
}

.way-for {
  margin-top: 2px;
  color: var(--secondary);
}

.way-meta {
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);

  code {
    font-size: 12px;
  }
}

.way-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 13px;
  line-height: 20px;
  font-weight: 500;
  color: var(--accent-text);

  svg {
    font-size: 11px;
  }

  // The whole card
  &::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: 12px;
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
  border-radius: 8px;
  scrollbar-width: none;

  button {
    flex: none;
    padding: 6px 14px;
    font: inherit;
    font-size: 13px;
    line-height: 20px;
    color: var(--secondary);
    background: none;
    border: 0;
    border-radius: 6px;
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
  border-radius: 8px;
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
  font-size: 12px;
  color: var(--secondary);
  background: #FFFFFF;
  border: 1px solid #A3A3A3;
  border-radius: 50%;
}

// Where it gets you
.step:last-child .marker {
  color: var(--accent-text);
  border-color: var(--accent);
}

.step-title {
  font-size: 15px;
  line-height: 28px;
  font-weight: 500;
}

.step-details {
  margin-top: 2px;
  max-width: 640px;
  font-size: 13px;
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
    font-size: 14px;
    line-height: 22px;
    font-weight: 600;
  }

  .explain > p {
    margin-top: 4px;
    font-size: 13px;
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
  border-radius: 10px;

  > p {
    margin-top: 6px;
    font-size: 13px;
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
    font-size: 14px;
    font-weight: 600;
    color: var(--ink);
  }
}

.token-card > .token-head {
  margin-top: 0;
}

.lasts {
  padding: 0 8px;
  font-size: 11.5px;
  line-height: 20px;
  color: var(--secondary);
  border: 1px solid var(--hairline);
  border-radius: 10px;
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
  font-size: 13px;
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
  font-size: 12px;
  line-height: 18px;
  color: var(--ink);
  word-break: break-all;
  background: #FAFAFA;
  border: 1px solid var(--hairline);
  border-radius: 8px;
  resize: vertical;
}

.error {
  margin-top: 8px;
  font-size: 13px;
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
    border-radius: 8px;

    &::placeholder {
      color: var(--muted);
    }

    &:hover {
      border-color: #A3A3A3;
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
  font-size: 13px;
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
  font-size: 13px;
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
    font-size: 12.5px;
    line-height: 20px;
    font-weight: 500;
    color: var(--ink);
    overflow-wrap: anywhere;
  }

  dd {
    margin: 2px 0 0;
    font-size: 13px;
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
