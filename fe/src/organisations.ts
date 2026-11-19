// Organisations as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls, Organisations.kt): Ogrodje's
// supporters and partners, and the companies people work at

import type { RouteLocationRaw } from 'vue-router'
import type { Region } from '@/events'
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
  meetupUrl: string | null
  eventbriteUrl: string | null
  icalUrl: string | null
  lumaUrl: string | null
  discordUrl: string | null
  kompotUrl: string | null
  mainColor: string | null
  backgroundColor: string | null
  collectsEvents: boolean
  archived: boolean
  /** Where its events usually are, which those that don't say where they are go by */
  venueName: string | null
  venueAddress: string | null
  /** Where its events usually are, for those with no region of their own: told from its venue, or the team's (regionManual) */
  region: Region | null
  regionManual: boolean
}

/** How one of an organisation's links was last scraped for events */
export interface EventScrape {
  field: string
  url: string
  // The scraper that read it; null when none takes it, or it failed
  source: string | null
  scrapedAt: string
  events: number
  error: string | null
}

export const EVENT_SCRAPE_FIELDS = 'field url source scrapedAt events error'

/** Where an organisation announces its events, which the scrapers read, as its website (EventScrape's field) */
export const EVENT_LINKS = [
  { key: 'meetupUrl', field: 'MEETUP', name: 'Meetup' },
  { key: 'eventbriteUrl', field: 'EVENTBRITE', name: 'Eventbrite' },
  { key: 'lumaUrl', field: 'LUMA', name: 'Luma' },
  { key: 'icalUrl', field: 'ICAL', name: 'iCal' },
  { key: 'kompotUrl', field: 'KOMPOT', name: 'Kompot' },
  { key: 'discordUrl', field: 'DISCORD', name: 'Discord' },
] as const

/** What an organisation is to be, as OrganisationInput has it: what's left out stays as it is; empty text clears it */
export type OrganisationInput = Partial<Record<
  'name' | 'description' | 'supportStartedAt' | 'supportEndedAt' | 'websiteUrl' | 'linkedinUrl' | 'twitterUrl'
  | 'instagramUrl' | 'facebookUrl' | 'vatId' | 'companyId' | 'address' | 'meetupUrl' | 'eventbriteUrl' | 'icalUrl'
  | 'lumaUrl' | 'discordUrl' | 'kompotUrl' | 'mainColor' | 'backgroundColor' | 'venueName' | 'venueAddress' | 'region', string>> & {
  tier?: OrganisationTier
  collectsEvents?: boolean
  archived?: boolean
}

/** The fields of a Supporter, for a query */
export const SUPPORTER_FIELDS = `id name tier websiteUrl logo { ${IMAGE_FIELDS} }`

/** The fields of an Organisation, for a query */
export const ORGANISATION_FIELDS = `
  ${SUPPORTER_FIELDS} description supporting supportStartedAt supportEndedAt linkedinUrl twitterUrl instagramUrl
  facebookUrl vatId companyId address contactPerson { id name } meetupUrl eventbriteUrl icalUrl lumaUrl discordUrl
  kompotUrl mainColor backgroundColor collectsEvents archived venueName venueAddress region regionManual
`

export const CREATE_ORGANISATION = `mutation ($input: OrganisationInput!) {
  organisation: createOrganisation(input: $input) { ${ORGANISATION_FIELDS} }
}`
export const UPDATE_ORGANISATION = `mutation ($id: ID!, $input: OrganisationInput!) {
  organisation: updateOrganisation(id: $id, input: $input) { ${ORGANISATION_FIELDS} }
}`
export const DELETE_ORGANISATION = 'mutation ($id: ID!) { deleteOrganisation(id: $id) }'

/** A colour as the badge takes it, #rrggbb, or none; what the colour field can't show, e.g. #fff, is none */
export function isColor(value: string | null | undefined): value is string {
  return !!value && /^#[0-9a-fA-F]{6}$/.test(value)
}


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
