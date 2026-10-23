<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRuntimeConfig } from '#imports'
import CopyCommand from '@/components/CopyCommand.vue'
import { usePageMeta } from '@/seo'
import { TOOL_GROUPS } from '@/tools'

// Where MCP clients connect, from the runtime config (nuxt.config.ts), so the server's page says it too
const { mcpUrl } = useRuntimeConfig().public

usePageMeta(() => ({
  description: 'Use MetaOS from your AI assistant over MCP: how to connect Claude Code, Claude, Codex or ChatGPT, and '
    + 'what it can do.',
}))

// The assistants the steps are for; their first two steps differ, approval and asking don't
type Client = 'claude-code' | 'claude' | 'codex' | 'chatgpt'
const CLIENTS: { id: Client, name: string }[] = [
  { id: 'claude-code', name: 'Claude Code' },
  { id: 'claude', name: 'Claude' },
  { id: 'codex', name: 'Codex' },
  { id: 'chatgpt', name: 'ChatGPT' },
]
const client = ref<Client>('claude-code')

// For all of the user's projects, not only the one they happen to be in
const claudeCommand = computed(() => `claude mcp add --transport http --scope user ogrodje ${mcpUrl}`)
// Codex's config.toml is the user's own (~/.codex), so it's in all their projects, and the IDE extension's too
const codexCommand = computed(() => `codex mcp add ogrodje --url ${mcpUrl}`)
const codexLogin = 'codex mcp login ogrodje'

const toolCount = TOOL_GROUPS.reduce((n, group) => n + group.tools.length, 0)
</script>

<template>
  <main>
    <h1 class="visually-hidden">Usage</h1>
    <section aria-labelledby="install">
      <div class="heading">
        <h2 id="install">Install</h2>
      </div>
      <p class="intro">
        MetaOS is an MCP server. Connect an AI assistant such as Claude, Codex or ChatGPT to it, and the assistant
        can look up and manage Ogrodje's episodes, people, recording sessions and shows, its Google Docs and its files
        in Koofr, as you.
      </p>

      <div class="clients" role="group" aria-label="Assistant">
        <button v-for="c in CLIENTS" :key="c.id" type="button" :aria-pressed="client === c.id" @click="client = c.id">
          {{ c.name }}
        </button>
      </div>

      <ol class="steps">
        <template v-if="client === 'claude-code'">
          <li class="step">
            <span class="number" aria-hidden="true">1</span>
            <div>
              <p class="title">Add MetaOS to Claude Code</p>
              <p class="details">In a terminal; <code>--scope user</code> makes it available in all your projects.</p>
              <CopyCommand :command="claudeCommand"/>
            </div>
          </li>
          <li class="step">
            <span class="number" aria-hidden="true">2</span>
            <div>
              <p class="title">Sign in</p>
              <p class="details">
                In Claude Code, run <code>/mcp</code>, pick <strong>ogrodje</strong> and choose Authenticate. Your
                browser opens MetaOS's sign-in, where you sign in with Discord or Google.
              </p>
            </div>
          </li>
        </template>

        <template v-else-if="client === 'claude'">
          <li class="step">
            <span class="number" aria-hidden="true">1</span>
            <div>
              <p class="title">Add MetaOS to Claude</p>
              <p class="details">
                On claude.ai or in Claude's desktop app, in <strong>Settings → Connectors</strong>, choose <strong>Add
                custom connector</strong>. Name it <strong>Ogrodje</strong>, with <code>{{ mcpUrl }}</code> as its URL,
                and leave the advanced settings empty. In a Team or Enterprise organization, an owner adds it for
                everyone in the organization's settings, and you connect it in yours.
              </p>
            </div>
          </li>
          <li class="step">
            <span class="number" aria-hidden="true">2</span>
            <div>
              <p class="title">Sign in</p>
              <p class="details">
                Choose <strong>Connect</strong> next to it: MetaOS's sign-in opens, where you sign in with Discord or
                Google. Claude's phone apps use it too, once it's connected. In a chat, turn it on in the
                <strong>+</strong> menu, under <strong>Connectors</strong>.
              </p>
            </div>
          </li>
        </template>

        <template v-else-if="client === 'codex'">
          <li class="step">
            <span class="number" aria-hidden="true">1</span>
            <div>
              <p class="title">Add MetaOS to Codex</p>
              <p class="details">
                In a terminal. It goes into your own <code>~/.codex/config.toml</code>, so it's in all your projects, in
                the CLI, Codex's IDE extension and its app.
              </p>
              <CopyCommand :command="codexCommand"/>
            </div>
          </li>
          <li class="step">
            <span class="number" aria-hidden="true">2</span>
            <div>
              <p class="title">Sign in</p>
              <p class="details">
                Adding it opens MetaOS's sign-in in your browser, where you sign in with Discord or Google. Codex keeps
                the sign-in and refreshes it; to sign in again, e.g. once you're approved:
              </p>
              <CopyCommand :command="codexLogin"/>
            </div>
          </li>
        </template>

        <template v-else>
          <li class="step">
            <span class="number" aria-hidden="true">1</span>
            <div>
              <p class="title">Add MetaOS to ChatGPT</p>
              <p class="details">
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
            <span class="number" aria-hidden="true">2</span>
            <div>
              <p class="title">Sign in</p>
              <p class="details">
                Creating it opens MetaOS's sign-in, where you sign in with Discord or Google and allow ChatGPT access.
                In a new chat, type <strong>@</strong> and pick <strong>Ogrodje</strong> for ChatGPT to use it.
              </p>
            </div>
          </li>
        </template>

        <li class="step">
          <span class="number" aria-hidden="true">3</span>
          <div>
            <p class="title">Get approved</p>
            <p class="details">
              The first time you sign in, an Ogrodje admin has to approve your account; until then MetaOS turns the
              sign-in down. Once you're approved, sign in again.
            </p>
          </div>
        </li>
        <li class="step">
          <span class="number" aria-hidden="true">4</span>
          <div>
            <p class="title">Ask</p>
            <p class="details">
              For example: “Which episodes are in production?” or “Who's on the next recording day?” The assistant picks
              the tools it needs from the ones below and, unless you've allowed them, asks you before it uses one.
            </p>
          </div>
        </li>
      </ol>

      <p class="note">
        Other MCP clients that sign in with OAuth, such as MCP Inspector, connect to <code>{{ mcpUrl }}</code>.
      </p>
    </section>

    <section aria-labelledby="tools">
      <div class="heading">
        <h2 id="tools">Tools</h2>
        <span class="count">{{ toolCount }}</span>
      </div>
      <div v-for="group in TOOL_GROUPS" :key="group.id" class="group">
        <h3 class="group-title">
          {{ group.title }}
          <span class="about">{{ group.about }}</span>
        </h3>
        <dl class="tools">
          <div v-for="tool in group.tools" :key="tool.name" class="tool">
            <dt><code>{{ tool.name }}</code></dt>
            <dd>{{ tool.description }}</dd>
          </div>
        </dl>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
// Sections' headings as the other pages have them; not page.scss, whose .title and .details are something else here
@use '../assets/heading';

// The other pages' column (page.scss)
main {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 48px 24px 96px;
  display: flex;
  flex-direction: column;
  gap: 56px;
}

code {
  font-family: var(--font-mono);
  font-size: 13px;
}

// Inline, in text
p code {
  padding: 1px 5px;
  background: var(--hover);
  border-radius: 4px;
}

strong {
  font-weight: 600;
  color: var(--ink);
}

.intro {
  max-width: 68ch;
  padding-bottom: 16px;
  font-size: 14px;
  line-height: 22px;
  color: var(--secondary);
}

// Which assistant the steps are for
.clients {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding-bottom: 8px;

  button {
    padding: 4px 12px;
    font: inherit;
    font-size: 13px;
    line-height: 20px;
    color: var(--secondary);
    background: none;
    border: 1px solid var(--hairline);
    border-radius: 6.4px;
    cursor: pointer;

    &:hover {
      border-color: #A3A3A3;
    }

    &[aria-pressed="true"] {
      font-weight: 500;
      color: var(--ink);
      border-color: var(--ink);
    }
  }
}

.steps, .tools {
  padding: 0;
  list-style: none;
}

// A step's number where an episode's code goes
.step {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  column-gap: 16px;
  padding: 14px 0;
  border-top: 1px solid var(--rule);
}

.number {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 22px;
  color: var(--muted);
}

.title {
  font-size: 15px;
  line-height: 22px;
  font-weight: 500;
}

.details {
  max-width: 62ch;
  margin-top: 4px;
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

.note {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--rule);

  code {
    color: var(--secondary);
    word-break: break-all;
  }
}

// Like the home page's recording sessions
.group-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: 8px;
  padding: 20px 0 8px;
  font-size: 13px;
  line-height: 20px;
  font-weight: 600;

  .about {
    font-weight: 400;
    color: var(--secondary);
  }
}

// Right under the section's heading, the heading's own space is enough
.heading + .group .group-title {
  padding-top: 8px;
}

.tool {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  column-gap: 16px;
  padding: 10px 0;
  border-top: 1px solid var(--rule);

  dt code {
    line-height: 22px;
    font-weight: 500;
    color: var(--ink);
  }

  dd {
    margin: 0;
    font-size: 14px;
    line-height: 22px;
    color: var(--secondary);
  }
}

// On phones the description goes under the tool's name, and a step's text under its number
@media (max-width: 640px) {
  // The other pages' narrower gutter (page.scss)
  main {
    padding: 32px 16px 64px;
    gap: 48px;
  }

  .step {
    grid-template-columns: 28px minmax(0, 1fr);
    column-gap: 8px;
  }

  .tool {
    grid-template-columns: minmax(0, 1fr);

    dd {
      margin-top: 2px;
    }
  }
}

</style>
