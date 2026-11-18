import { describe, expect, it } from 'vitest'
import { byTier, isColor, isWordmark, organisationPath, siteName, type Supporter, tierName, workPeriod } from '@/organisations'
import { t, tSl } from './i18n'

const organisation = (name: string, tier: Supporter['tier']): Supporter =>
  ({ id: name, name, tier, websiteUrl: null, logo: null })

describe('organisations', () => {
  it('lists supporters before partners, each as MetaOS ordered them, and leaves out the rest', () => {
    const listed = byTier([
      organisation('Monitor', 'PARTNER'), organisation('Kaldi', 'SUPPORTER'), organisation('Acme', 'NONE'),
      organisation('Koofr', 'SUPPORTER'),
    ])
    expect(listed.map((group) => [group.tier, group.organisations.map((o) => o.name)])).toEqual([
      ['SUPPORTER', ['Kaldi', 'Koofr']],
      ['PARTNER', ['Monitor']],
    ])
    expect(byTier([organisation('Acme', 'NONE')])).toEqual([])
  })

  it('names the tiers as ogrodje.si did', () => {
    expect(tierName('SUPPORTER', tSl)).toBe('Podporniki')
    expect(tierName('PARTNER', tSl)).toBe('Partnerji')
    expect(tierName('SUPPORTER', t)).toBe('Supporters')
  })

  it("tells a word mark, which has the name in it, from a logo that doesn't", () => {
    expect(isWordmark({ width: 431, height: 119 })).toBe(true)
    expect(isWordmark({ width: 512, height: 512 })).toBe(false)
    expect(isWordmark({ width: 829, height: 1044 })).toBe(false)
    expect(isWordmark(null)).toBe(false)
  })

  it('says a website as people do', () => {
    expect(siteName('https://www.visionect.com')).toBe('visionect.com')
    expect(siteName('https://koofr.eu/')).toBe('koofr.eu')
    expect(siteName('http://wwwhmb.si/')).toBe('wwwhmb.si')
  })

  it('says when someone worked somewhere, by the month', () => {
    expect(workPeriod({ startedAt: '2024-03-01', endedAt: null }, t, 'en-GB')).toBe('since Mar 2024')
    expect(workPeriod({ startedAt: '2020-01-01', endedAt: '2023-12-31' }, t, 'en-GB')).toBe('Jan 2020 – Dec 2023')
    expect(workPeriod({ startedAt: null, endedAt: '2023-12-31' }, t, 'en-GB')).toBe('until Dec 2023')
    expect(workPeriod({ startedAt: null, endedAt: null }, t, 'en-GB')).toBeNull()
    expect(workPeriod({ startedAt: '2024-03-01', endedAt: null }, tSl, 'sl-SI')).toBe('od mar. 2024')
  })

  it('leads to its page by its route, as its address is another word in English', () => {
    expect(organisationPath({ id: 'abc' })).toEqual({ name: 'organisation', params: { id: 'abc' } })
  })
})

describe('isColor', () => {
  it('takes a colour as the badge does, #rrggbb', () => {
    expect(isColor('#1a2B3c')).toBe(true)
    expect(['#fff', '1a2b3c', '', null, undefined, '#1a2b3cff'].map(isColor)).toEqual([false, false, false, false, false, false])
  })
})
