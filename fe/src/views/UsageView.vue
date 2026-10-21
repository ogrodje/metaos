<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { faCopy } from '@fortawesome/free-regular-svg-icons'
import { faCheck } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { TOOL_GROUPS } from '@/tools'

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

// For all of the user's projects, not only the one they happen to be in
const command = computed(() => `claude mcp add --transport http --scope user ogrodje ${mcpUrl.value}`)

const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(command.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch (error) {
    console.error('The command was not copied', error)
  }
}

const toolCount = TOOL_GROUPS.reduce((n, group) => n + group.tools.length, 0)
</script>

<template>
  <main>
    <section aria-labelledby="install">
      <div class="heading">
        <h2 id="install">Install</h2>
      </div>
      <p class="intro">
        MetaOS is an MCP server. Connect an AI assistant such as Claude Code to it, and the assistant can look up and
        manage Ogrodje's episodes, people, recording sessions and shows, its Google Docs and its files in Koofr, as you.
      </p>

      <ol class="steps">
        <li class="step">
          <span class="number" aria-hidden="true">1</span>
          <div>
            <p class="title">Add MetaOS to Claude Code</p>
            <p class="details">In a terminal; <code>--scope user</code> makes it available in all your projects.</p>
            <div class="command">
              <code>{{ command }}</code>
              <button type="button" class="icon-link" :aria-label="copied ? 'Copied' : 'Copy the command'"
                      :title="copied ? 'Copied' : 'Copy'" @click="copy">
                <FontAwesomeIcon :icon="copied ? faCheck : faCopy"/>
              </button>
            </div>
          </div>
        </li>
        <li class="step">
          <span class="number" aria-hidden="true">2</span>
          <div>
            <p class="title">Sign in</p>
            <p class="details">
              In Claude Code, run <code>/mcp</code>, pick <strong>ogrodje</strong> and choose Authenticate. Your browser
              opens MetaOS's sign-in, where you sign in with Discord or Google.
            </p>
          </div>
        </li>
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
              For example: “Which episodes are in production?” or “Who's on the next recording day?” Claude picks the
              tools it needs from the ones below and, unless you've allowed them, asks you before it uses one.
            </p>
          </div>
        </li>
      </ol>

      <p class="note">
        Other MCP clients that sign in with OAuth, such as MCP Inspector, claude.ai's connectors and ChatGPT, connect to
        <code>{{ mcpUrl }}</code>. In ChatGPT, add it as a custom MCP server with OAuth and Dynamic Client Registration
        (DCR).
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

// The home page's shell, headings and rows (HomeView, EpisodeItem)
main {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 48px 24px 96px;
  display: flex;
  flex-direction: column;
  gap: 56px;
}

.heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-bottom: 12px;

  h2 {
    font-size: 15px;
    line-height: 24px;
    font-weight: 600;
  }
}

.count, .note {
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
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
  max-width: 640px;
  padding-bottom: 16px;
  font-size: 14px;
  line-height: 22px;
  color: var(--secondary);
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
  margin-top: 4px;
  font-size: 13px;
  line-height: 20px;
  color: var(--muted);
}

.command {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 4px 4px 4px 14px;
  background: var(--hover);
  border: 1px solid var(--hairline);
  border-radius: 8px;

  // Scrolls on its own rather than widening the page
  code {
    flex: 1;
    overflow-x: auto;
    padding: 5px 0;
    line-height: 22px;
    white-space: nowrap;
    color: var(--ink);
  }

  button {
    flex: none;
    color: var(--secondary);
    background: none;
    border: 0;
    cursor: pointer;

    &:hover {
      background: #FFFFFF;
    }
  }
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
