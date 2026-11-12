import { describe, expect, it } from 'vitest'
import { asToggles, FLAGS, flagsOn, isOn } from '@/flags'
import { links } from '@/episodes'

describe('isOn', () => {
  it("is a flag's default while Unleash hasn't said", () => {
    expect(isOn(null, 'patreon-links')).toBe(FLAGS['patreon-links'])
  })

  it('is what Unleash said once it has: off when it lists it not', () => {
    expect(isOn(['patreon-links'], 'patreon-links')).toBe(true)
    expect(isOn([], 'patreon-links')).toBe(false)
  })
})

describe('flagsOn', () => {
  it('keeps those that are on, by name', () => {
    expect(flagsOn([{ name: 'b', enabled: true }, { name: 'off', enabled: false }, { name: 'a', enabled: true }]))
      .toEqual(['a', 'b'])
  })

  it('makes them again as the SDK takes them, to start from what the server rendered with', () => {
    expect(asToggles(null)).toBeUndefined()
    expect(flagsOn(asToggles(['patreon-links'])!)).toEqual(['patreon-links'])
  })
})

describe('links, by flag patreon-links', () => {
  const episode = {
    youtubeUrl: 'https://youtu.be/x', patreonUrl: 'https://www.patreon.com/posts/v',
    patreonAudioUrl: 'https://www.patreon.com/posts/a', patreonBonusUrl: null,
    patreonBonusAudioUrl: 'https://www.patreon.com/posts/ba',
  }

  it("lists Patreon's video only while it's off", () => {
    expect(links(episode).map((link) => link.platform)).toEqual(['YouTube', 'Patreon'])
  })

  it('lists its audio and bonus on Patreon too once on', () => {
    expect(links(episode, true).map((link) => link.platform))
      .toEqual(['YouTube', 'Patreon', 'Patreon audio', 'Patreon bonus audio'])
  })
})
