import { describe, expect, it } from 'vitest'
import { browserAgentOptions } from '@/newrelic'

const settings = { accountId: '8666724', trustKey: '8666724', agentId: '538000001', licenseKey: 'NRJS-0123456789abcdef012', applicationId: '538000001' }

describe('browserAgentOptions', () => {
  it('is off without the browser app', () => {
    expect(browserAgentOptions({ accountId: '', trustKey: '', agentId: '', licenseKey: '', applicationId: '' })).toBeNull()
    expect(browserAgentOptions({ ...settings, applicationId: '' })).toBeNull()
  })

  it('reports to the EU region, as the browser app, with traces into MetaOS', () => {
    const options = browserAgentOptions(settings)!
    expect(options.info).toMatchObject({ beacon: 'bam.eu01.nr-data.net', errorBeacon: 'bam.eu01.nr-data.net', licenseKey: settings.licenseKey, applicationID: '538000001' })
    expect(options.loader_config).toEqual({ accountID: '8666724', trustKey: '8666724', agentID: '538000001', licenseKey: settings.licenseKey, applicationID: '538000001' })
    expect(options.init?.distributed_tracing?.enabled).toBe(true)
  })

  it('keeps nothing in the visitor\'s browser', () => {
    const { init } = browserAgentOptions(settings)!
    expect(init?.privacy?.cookies_enabled).toBe(false)
    expect(init?.session_replay?.enabled).toBe(false)
  })
})
