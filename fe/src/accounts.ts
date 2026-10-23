// Accounts as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls): the Keycloak users it has seen,
// each linked to the person it is (Accounts.kt)

import { fold, searchWords } from '@/search'

/** How an account was linked to its person: by its verified email, or by someone */
export type AccountLink = 'EMAIL' | 'MANUAL'

/** A Keycloak user MetaOS has seen, which may sign in with several identity providers, and the person it is */
export interface Account {
  id: string
  // Keycloak's, e.g. the email for a Google sign-in
  username: string | null
  name: string | null
  email: string | null
  emailVerified: boolean
  person: { id: string, name: string } | null
  linkedHow: AccountLink | null
  linkedAt: string | null
  // By name; null when its email did
  linkedBy: string | null
  createdAt: string
}

/** An account's fields, for a query */
export const ACCOUNT_FIELDS = 'id username name email emailVerified person { id name } linkedHow linkedAt linkedBy createdAt'

export const LINK_ACCOUNT = `mutation ($id: ID!, $person: ID!) { account: linkAccount(id: $id, person: $person) { ${ACCOUNT_FIELDS} } }`
export const UNLINK_ACCOUNT = `mutation ($id: ID!) { account: unlinkAccount(id: $id) { ${ACCOUNT_FIELDS} } }`

/** A sign-in's name: Keycloak's username, or else its email or name */
export function accountName(account: Pick<Account, 'username' | 'email' | 'name'>) {
  return account.username ?? account.email ?? account.name ?? 'A sign-in'
}

/** A day as the sign-ins say it, e.g. 23 Oct; with its year when it's not this year's, e.g. 19 Oct 2025 */
export function shortDate(at: string, now = new Date(), locale?: string) {
  const date = new Date(at)
  return date.toLocaleDateString(locale, {
    day: 'numeric',
    month: 'short',
    ...(date.getFullYear() !== now.getFullYear() ? { year: 'numeric' } : {}),
  })
}

/** How and when an account was linked to its person, e.g. linked by email, 23 Oct; null while it isn't */
export function howLinked(account: Pick<Account, 'linkedHow' | 'linkedAt' | 'linkedBy'>, now = new Date(), locale?: string) {
  if (!account.linkedHow) return null
  const how = account.linkedHow === 'EMAIL' ? 'linked by email' : `linked by ${account.linkedBy ?? 'someone'}`
  return account.linkedAt ? `${how}, ${shortDate(account.linkedAt, now, locale)}` : how
}

/** When MetaOS first saw an account, e.g. first seen 21 Oct */
export function firstSeen(account: Pick<Account, 'createdAt'>, now = new Date(), locale?: string) {
  return `first seen ${shortDate(account.createdAt, now, locale)}`
}

/**
 * What a person's page says of one of their sign-ins besides its name: its email, when that isn't its name, and how
 * and when it was linked, e.g. dev@metaos.local · linked by Oto Brglez, 19 Oct
 */
export function signInDetails(account: Account, now = new Date(), locale?: string) {
  const shown = accountName(account)
  return [account.email !== shown ? account.email : null, howLinked(account, now, locale)].filter(Boolean).join(' · ')
}

/**
 * What the People page says of a sign-in nobody's linked to besides its name: Keycloak's name for it and its email,
 * when they aren't that, and when MetaOS first saw it, e.g. Maja Kovač · maja@example.com · first seen 21 Oct
 */
export function unlinkedDetails(account: Account, now = new Date(), locale?: string) {
  const shown = accountName(account)
  return [
    account.name !== shown ? account.name : null,
    account.email !== shown ? account.email : null,
    firstSeen(account, now, locale),
  ].filter(Boolean).join(' · ')
}

/**
 * Who a picker offers for [text]: those whose name has each of its words, at the start of one of its words or, from
 * three letters on, anywhere in it, as search finds them (fold: case and diacritics don't matter). Those whose name
 * starts with it first, in the order they came otherwise; at most [limit].
 */
export function findPeople<T extends { name: string }>(people: T[], text: string, limit = 8): T[] {
  const words = searchWords(text)
  if (!words.length) return []
  const matches = people.filter((person) => {
    const name = fold(person.name)
    const names = searchWords(person.name)
    return words.every((word) => names.some((part) => part.startsWith(word)) || (word.length >= 3 && name.includes(word)))
  })
  const typed = words.join(' ')
  const first = (person: T) => searchWords(person.name).join(' ').startsWith(typed)
  return [...matches.filter(first), ...matches.filter((person) => !first(person))].slice(0, limit)
}

// Signing in with another identity provider, through Keycloak (stores/auth.ts)

/**
 * One of the realm's identity providers, e.g. Discord, as Keycloak's Account REST API has it
 * (GET <issuer>/account/linked-accounts), and whether the signed-in user's Keycloak user signs in with it
 */
export interface SignInProvider {
  // Its alias in the realm, e.g. discord
  alias: string
  // e.g. Discord
  name: string
  connected: boolean
  // Who they are there, e.g. their Discord username; null when it isn't connected, or Keycloak didn't say
  username: string | null
}

function withoutSlash(issuer: string) {
  return issuer.replace(/\/+$/, '')
}

/** Where Keycloak's Account REST API lists the realm's identity providers and the user's links to them */
export function linkedAccountsUrl(issuer: string) {
  return `${withoutSlash(issuer)}/account/linked-accounts`
}

/** The page of Keycloak's Account Console where users link and unlink identity providers themselves */
export function accountConsoleUrl(issuer: string) {
  return `${withoutSlash(issuer)}/account/account-security/linked-accounts`
}

function text(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

/**
 * Keycloak's linked accounts, read defensively, as nothing in MetaOS asked for them before: entries without an alias
 * are left out, and an alias already listed. Anything but a list is an error, as the menu then links to the Account
 * Console instead.
 */
export function parseLinkedAccounts(answer: unknown): SignInProvider[] {
  if (!Array.isArray(answer)) throw new Error("Keycloak didn't list the identity providers")
  const listed = new Set<string>()
  return answer.flatMap((entry: unknown) => {
    if (typeof entry !== 'object' || entry === null) return []
    const { providerAlias, displayName, connected, linkedUsername } = entry as Record<string, unknown>
    const alias = text(providerAlias)
    if (!alias || listed.has(alias)) return []
    listed.add(alias)
    const isConnected = connected === true
    return [{
      alias,
      name: text(displayName) ?? providerName(alias),
      connected: isConnected,
      username: isConnected ? text(linkedUsername) : null,
    }]
  })
}

/** The realm's identity providers and the user's links to them, from Keycloak with the user's [token] */
export async function loadSignInProviders(issuer: string, token: string, fetcher: typeof fetch = fetch) {
  const response = await fetcher(linkedAccountsUrl(issuer), {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
  })
  if (!response.ok) throw new Error(`Keycloak answered ${response.status}`)
  return parseLinkedAccounts(await response.json())
}

/** An identity provider's name when Keycloak doesn't give one: its alias, capitalised, e.g. Discord */
export function providerName(alias: string) {
  return alias.charAt(0).toUpperCase() + alias.slice(1)
}

const LINK_ACTION = 'idp_link:'

/** Keycloak's application-initiated action that links [alias] to the signed-in user, as keycloak-js's login takes it */
export function linkAction(alias: string) {
  return `${LINK_ACTION}${alias}`
}

/** The identity provider an action linked, e.g. discord for idp_link:discord; null for any other action */
export function linkedAlias(action: string | undefined) {
  return action?.startsWith(LINK_ACTION) ? action.slice(LINK_ACTION.length) || null : null
}

/** What the page says once it's back from linking an identity provider: whether it's linked, in words for the user */
export interface LinkOutcome {
  linked: boolean
  text: string
}

/**
 * How linking [provider] went, from Keycloak's kc_action_status (success, cancelled or error), or from an error it sent
 * instead ([detail], its error_description)
 */
export function linkOutcome(status: string | undefined, provider: string, detail?: string | null): LinkOutcome {
  if (status === 'success') return { linked: true, text: `${provider} is linked: you can sign in with it too.` }
  if (status === 'cancelled') return { linked: false, text: `${provider} wasn't linked, as you cancelled it.` }
  if (detail) return { linked: false, text: `${provider} wasn't linked: ${detail.replace(/\.?$/, '.')}` }
  return { linked: false, text: `${provider} wasn't linked: that ${provider} account may be linked to another sign-in already.` }
}
