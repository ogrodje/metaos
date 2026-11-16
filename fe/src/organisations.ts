// Organisations as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls, Organisations.kt): Ogrodje's
// supporters and partners, and the companies people work at

import type { RouteLocationRaw } from 'vue-router'
import type { Translate } from '@/i18n'
import { type Image, IMAGE_FIELDS } from '@/images'

/** How an organisation supports Ogrodje, as ogrodje.si's footer had them: Podporniki, Partnerji; or not at all */
export type OrganisationTier = 'SUPPORTER' | 'PARTNER' | 'NONE'

/** Supporters before partners, as the home page and the footer list them */
export const TIERS: Exclude<OrganisationTier, 'NONE'>[] = ['SUPPORTER', 'PARTNER']

/** An organisation as anyone may see it, e.g. among the supporters */
export interface Supporter {
  id: string
  name: string
  tier: OrganisationTier
  websiteUrl: string | null
  logo: Image | null
}

/** Someone's work at an organisation */
export interface Work {
  id: string
  title: string | null
  startedAt: string | null
  endedAt: string | null
  current: boolean
}

/** An organisation as the team sees it: what's the team's is null, or empty, for anyone else */
export interface Organisation extends Supporter {
  description: string | null
  supporting: boolean
  supportStartedAt: string | null
  supportEndedAt: string | null
  linkedinUrl: string | null
  twitterUrl: string | null
  instagramUrl: string | null
  facebookUrl: string | null
  vatId: string | null
  companyId: string | null
  address: string | null
  contactPerson: { id: string, name: string } | null
}

/** The fields of a Supporter, for a query */
export const SUPPORTER_FIELDS = `id name tier websiteUrl logo { ${IMAGE_FIELDS} }`

/** The fields of an Organisation, for a query */
export const ORGANISATION_FIELDS = `
  ${SUPPORTER_FIELDS} description supporting supportStartedAt supportEndedAt linkedinUrl twitterUrl instagramUrl
  facebookUrl vatId companyId address contactPerson { id name }
`

/** The fields of someone's work at an organisation, for a query */
export const WORK_FIELDS = 'id title startedAt endedAt current'

/**
 * An organisation's page, by its route's name, as its address is another in each language: /organizacije/<ID> in
 * Slovenian, /en/organisations/<ID> in English (router/index.ts); the page's `path` puts it in its language
 */
export function organisationPath(organisation: Pick<Supporter, 'id'>): RouteLocationRaw {
  return { name: 'organisation', params: { id: organisation.id } }
}

/** The list of organisations, as organisationPath */
export const ORGANISATIONS_PATH: RouteLocationRaw = { name: 'organisations' }

/** A tier's name in the page's language, e.g. Podporniki, as a heading for its organisations */
export function tierName(tier: OrganisationTier, t: Translate) {
  return t(`organisations.tiers.${tier}`)
}

/** The supporters by tier, supporters first, each as MetaOS orders them (by name); tiers without any left out */
export function byTier<T extends Pick<Supporter, 'tier'>>(organisations: T[]) {
  return TIERS.flatMap((tier) => {
    const listed = organisations.filter((organisation) => organisation.tier === tier)
    return listed.length ? [{ tier, organisations: listed }] : []
  })
}

/**
 * Whether a logo has the name in it, as a word mark does, e.g. Monitor's: one at least twice as wide as it's tall,
 * shown without the name beside it, as ogrodje.si did
 */
export function isWordmark(logo: Pick<Image, 'width' | 'height'> | null) {
  return !!logo && logo.width >= 2 * logo.height
}

/** A website's address as people say it: without its scheme, www. and a trailing slash, e.g. koofr.eu */
export function siteName(url: string) {
  return url.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '')
}

/** When someone worked somewhere, in the page's language, e.g. Mar 2024 – now, or since when only */
export function workPeriod(work: Pick<Work, 'startedAt' | 'endedAt'>, t: Translate, dateLocale: string) {
  const month = (day: string) =>
    new Date(`${day}T12:00:00Z`).toLocaleDateString(dateLocale, { month: 'short', year: 'numeric', timeZone: 'UTC' })
  if (!work.startedAt && !work.endedAt) return null
  if (!work.endedAt) return t('organisations.since', { from: month(work.startedAt!) })
  if (!work.startedAt) return t('organisations.until', { to: month(work.endedAt) })
  return `${month(work.startedAt)} – ${month(work.endedAt)}`
}
