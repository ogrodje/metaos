import type { AgentOptions } from '@newrelic/browser-agent/loaders/agent'

// New Relic's browser agent (plugins/newrelic.client.ts): the page's errors, loads and Core Web Vitals, and each request
// to MetaOS as the start of its trace there. Its settings are the browser app's in New Relic, from the runtime config
// (nuxt.config.ts), so one build works in every environment; none, as in development without them, and it's off.

export interface NewRelicSettings {
  accountId: string
  trustKey: string
  agentId: string
  licenseKey: string
  applicationId: string
}

// The account is in New Relic's EU region, so its data stays there
const BEACON = 'bam.eu01.nr-data.net'

/** The agent's options, or none without the settings */
export function browserAgentOptions(settings: NewRelicSettings): AgentOptions | null {
  const { accountId, trustKey, agentId, licenseKey, applicationId } = settings
  if (!licenseKey || !applicationId) return null
  return {
    info: { beacon: BEACON, errorBeacon: BEACON, licenseKey, applicationID: applicationId, sa: 1 },
    loader_config: { accountID: accountId, trustKey, agentID: agentId, licenseKey, applicationID: applicationId },
    init: {
      // MetaOS's requests are the page's own origin, which gets the trace's headers without asking
      distributed_tracing: { enabled: true },
      // Nothing kept in the visitor's browser (cookies, storage), so nothing to ask them about; without it the agent
      // doesn't tie a visit's pages together, nor record sessions to replay
      privacy: { cookies_enabled: false },
      session_replay: { enabled: false },
      session_trace: { enabled: false },
      ajax: { deny_list: [BEACON] },
    },
  }
}
