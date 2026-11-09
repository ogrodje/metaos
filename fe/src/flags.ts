import type { IToggle } from 'unleash-proxy-client'

/**
 * The front-end's feature flags, by their names in Unleash, each with what it is while Unleash can't say (CLAUDE.md,
 * "Feature flags"). A flag the front-end reads has to be here, and in Unleash, where it's turned on and off.
 */
export const FLAGS = {
  // An episode's page lists its audio and bonus on Patreon too, not only its video (ListenLinks.vue)
  'patreon-links': false,
} satisfies Record<string, boolean>

export type Flag = keyof typeof FLAGS

/**
 * The flags that are on, as Unleash last said, or null while it hasn't: its frontend API lists only those that are on,
 * so once it's answered, one it didn't list is off, whatever its default.
 */
export type FlagsState = string[] | null

export function isOn(state: FlagsState, flag: Flag): boolean {
  return state ? state.includes(flag) : FLAGS[flag]
}

/** What Unleash's answer says is on */
export function flagsOn(toggles: Pick<IToggle, 'name' | 'enabled'>[]): string[] {
  return toggles.filter((toggle) => toggle.enabled).map((toggle) => toggle.name).sort()
}

/** The same flags on again, as Unleash's SDK takes them, to start from what the server rendered with */
export function asToggles(state: FlagsState): IToggle[] | undefined {
  return state?.map((name) => ({ name, enabled: true, variant: { name: 'disabled', enabled: false }, impressionData: false }))
}
