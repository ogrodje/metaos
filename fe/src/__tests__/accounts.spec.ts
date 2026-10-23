import { describe, expect, it } from 'vitest'
import {
  type Account, accountConsoleUrl, accountName, findPeople, firstSeen, howLinked, linkAction, linkedAccountsUrl,
  linkedAlias, linkOutcome, loadSignInProviders, parseLinkedAccounts, shortDate, signInDetails, unlinkedDetails,
} from '@/accounts'

const ISSUER = 'https://metaos-auth.ogrodje.si/realms/metaos'
const NOW = new Date('2026-10-23T12:00:00Z')

function account(fields: Partial<Account> = {}): Account {
  return {
    id: 'f1c6a9d2',
    username: 'otobrglez@gmail.com',
    name: 'Oto Brglez',
    email: 'otobrglez@gmail.com',
    emailVerified: true,
    person: { id: 'p1', name: 'Oto Brglez' },
    linkedHow: 'EMAIL',
    linkedAt: '2026-10-23T09:30:00Z',
    linkedBy: null,
    createdAt: '2026-10-19T12:00:00Z',
    ...fields,
  }
}

describe('parseLinkedAccounts', () => {
  it("reads Keycloak's linked accounts", () => {
    expect(parseLinkedAccounts([
      { connected: true, social: true, providerAlias: 'google', providerName: 'google', displayName: 'Google', linkedUsername: 'otobrglez@gmail.com' },
      { connected: false, social: true, providerAlias: 'discord', providerName: 'oidc', displayName: 'Discord' },
    ])).toEqual([
      { alias: 'google', name: 'Google', connected: true, username: 'otobrglez@gmail.com' },
      { alias: 'discord', name: 'Discord', connected: false, username: null },
    ])
  })

  it('names a provider by its alias when Keycloak has no display name for it', () => {
    expect(parseLinkedAccounts([{ providerAlias: 'discord', displayName: ' ', connected: false }])[0]?.name).toBe('Discord')
    expect(parseLinkedAccounts([{ providerAlias: 'github', displayName: null }])[0]?.name).toBe('Github')
  })

  it("takes only true for connected, and a username only from one that's connected", () => {
    expect(parseLinkedAccounts([
      { providerAlias: 'google', connected: 'true', linkedUsername: 'someone' },
      { providerAlias: 'discord', connected: true, linkedUsername: 42 },
    ])).toEqual([
      { alias: 'google', name: 'Google', connected: false, username: null },
      { alias: 'discord', name: 'Discord', connected: true, username: null },
    ])
  })

  it('leaves out entries without an alias, and an alias listed already', () => {
    expect(parseLinkedAccounts([
      null, 'google', 7, [], {}, { providerAlias: '' }, { displayName: 'Nameless' },
      { providerAlias: 'google', displayName: 'Google' },
      { providerAlias: 'google', displayName: 'Google again' },
    ]).map((provider) => provider.name)).toEqual(['Google'])
  })

  it("fails on anything but a list, so the menu links to Keycloak's Account Console", () => {
    expect(() => parseLinkedAccounts({ error: 'unknown_error' })).toThrow()
    expect(() => parseLinkedAccounts(null)).toThrow()
    expect(() => parseLinkedAccounts('[]')).toThrow()
  })
})

describe('loadSignInProviders', () => {
  it("asks Keycloak's Account REST API with the user's token", async () => {
    const asked: { url: string, headers: unknown }[] = []
    const fetcher = (async (url: string, init?: RequestInit) => {
      asked.push({ url, headers: init?.headers })
      return new Response(JSON.stringify([{ providerAlias: 'discord', displayName: 'Discord', connected: false }]))
    }) as typeof fetch
    expect(await loadSignInProviders(ISSUER, 'token', fetcher)).toEqual([
      { alias: 'discord', name: 'Discord', connected: false, username: null },
    ])
    expect(asked).toEqual([{
      url: `${ISSUER}/account/linked-accounts`,
      headers: { Authorization: 'Bearer token', Accept: 'application/json' },
    }])
  })

  it('fails when Keycloak refuses, e.g. a token without the account roles', async () => {
    const fetcher = (async () => new Response('', { status: 403 })) as typeof fetch
    await expect(loadSignInProviders(ISSUER, 'token', fetcher)).rejects.toThrow('Keycloak answered 403')
  })
})

describe("Keycloak's addresses", () => {
  it('are under the issuer, with or without its slash', () => {
    expect(linkedAccountsUrl(`${ISSUER}/`)).toBe(`${ISSUER}/account/linked-accounts`)
    expect(accountConsoleUrl(ISSUER)).toBe(`${ISSUER}/account/account-security/linked-accounts`)
  })
})

describe('linkAction and linkedAlias', () => {
  it("are Keycloak's idp_link action, there and back", () => {
    expect(linkAction('discord')).toBe('idp_link:discord')
    expect(linkedAlias('idp_link:discord')).toBe('discord')
  })

  it('know no provider in other actions', () => {
    expect(linkedAlias('UPDATE_PASSWORD')).toBeNull()
    expect(linkedAlias('idp_link:')).toBeNull()
    expect(linkedAlias(undefined)).toBeNull()
  })
})

describe('linkOutcome', () => {
  it('says a provider is linked', () => {
    expect(linkOutcome('success', 'Discord')).toEqual({ linked: true, text: 'Discord is linked: you can sign in with it too.' })
  })

  it("says it wasn't, and why", () => {
    expect(linkOutcome('cancelled', 'Discord')).toEqual({ linked: false, text: "Discord wasn't linked, as you cancelled it." })
    expect(linkOutcome('error', 'Discord').text)
      .toBe("Discord wasn't linked: that Discord account may be linked to another sign-in already.")
  })

  it("passes on what Keycloak said went wrong, as a sentence", () => {
    expect(linkOutcome('error', 'Discord', 'Identity provider is disabled').text)
      .toBe("Discord wasn't linked: Identity provider is disabled.")
    expect(linkOutcome(undefined, 'Google', 'Not allowed.').text).toBe("Google wasn't linked: Not allowed.")
  })

  it("takes a status it doesn't know for an error", () => {
    expect(linkOutcome('pending', 'Discord').linked).toBe(false)
  })
})

describe('accountName', () => {
  it("is Keycloak's username, or else the email or the name", () => {
    expect(accountName(account())).toBe('otobrglez@gmail.com')
    expect(accountName(account({ username: null }))).toBe('otobrglez@gmail.com')
    expect(accountName(account({ username: null, email: null }))).toBe('Oto Brglez')
    expect(accountName(account({ username: null, email: null, name: null }))).toBe('A sign-in')
  })
})

describe('howLinked', () => {
  it('says by email or by whom, and when', () => {
    expect(howLinked(account(), NOW, 'en-GB')).toBe('linked by email, 23 Oct')
    expect(howLinked(account({ linkedHow: 'MANUAL', linkedBy: 'Oto Brglez', linkedAt: '2026-10-19T12:00:00Z' }), NOW, 'en-GB'))
      .toBe('linked by Oto Brglez, 19 Oct')
  })

  it('says someone when it was linked by hand by whom MetaOS no longer knows', () => {
    expect(howLinked(account({ linkedHow: 'MANUAL', linkedBy: null }), NOW, 'en-GB')).toBe('linked by someone, 23 Oct')
  })

  it("has the year when it isn't this year's", () => {
    expect(howLinked(account({ linkedAt: '2025-10-19T12:00:00Z' }), NOW, 'en-GB')).toBe('linked by email, 19 Oct 2025')
  })

  it("says nothing of an account that isn't linked, and no date it doesn't have", () => {
    expect(howLinked(account({ linkedHow: null, linkedAt: null, person: null }), NOW)).toBeNull()
    expect(howLinked(account({ linkedAt: null }), NOW)).toBe('linked by email')
  })
})

describe('shortDate', () => {
  it('is the day and the month', () => {
    expect(shortDate('2026-01-02T12:00:00Z', NOW, 'en-GB')).toBe('2 Jan')
  })
})

describe('signInDetails', () => {
  it("has the email when it isn't the account's name, and how it was linked", () => {
    expect(signInDetails(account(), NOW, 'en-GB')).toBe('linked by email, 23 Oct')
    expect(signInDetails(account({
      username: 'dev', email: 'dev@metaos.local', linkedHow: 'MANUAL', linkedBy: 'Oto Brglez', linkedAt: '2026-10-19T12:00:00Z',
    }), NOW, 'en-GB')).toBe('dev@metaos.local · linked by Oto Brglez, 19 Oct')
  })
})

describe('unlinkedDetails', () => {
  it("has Keycloak's name and the email when they aren't the account's name, and when MetaOS first saw it", () => {
    const maja = account({
      username: 'maja.k', name: 'Maja Kovač', email: 'maja@example.com', person: null, linkedHow: null, linkedAt: null,
      createdAt: '2026-10-21T12:00:00Z',
    })
    expect(unlinkedDetails(maja, NOW, 'en-GB')).toBe('Maja Kovač · maja@example.com · first seen 21 Oct')
    expect(unlinkedDetails({ ...maja, name: null, email: null }, NOW, 'en-GB')).toBe('first seen 21 Oct')
    expect(firstSeen(maja, NOW, 'en-GB')).toBe('first seen 21 Oct')
  })
})

describe('findPeople', () => {
  const people = [
    { id: '1', name: 'Oto Brglez' },
    { id: '2', name: 'Maja Kovač' },
    { id: '3', name: 'dr. Matej Čuhalev' },
    { id: '4', name: 'Andraž Brglez' },
    { id: '5', name: 'Tomaž Ota' },
    { id: '6', name: 'Ota Novak' },
  ]
  const names = (text: string, limit?: number) => findPeople(people, text, limit).map((person) => person.name)

  it('finds names by the start of their words, whatever the case and diacritics', () => {
    expect(names('kova')).toEqual(['Maja Kovač'])
    expect(names('ČUH')).toEqual(['dr. Matej Čuhalev'])
    expect(names('cuhalev matej')).toEqual(['dr. Matej Čuhalev'])
    expect(names('andraz')).toEqual(['Andraž Brglez'])
  })

  it('finds names with each word typed', () => {
    expect(names('brglez')).toEqual(['Oto Brglez', 'Andraž Brglez'])
    expect(names('brglez oto')).toEqual(['Oto Brglez'])
    expect(names('brglez maja')).toEqual([])
  })

  it('finds inside words from three letters on', () => {
    expect(names('ova')).toEqual(['Maja Kovač', 'Ota Novak'])
    expect(names('ov')).toEqual([])
  })

  it("puts those whose name starts with what's typed first, in the order they came otherwise", () => {
    expect(names('ot')).toEqual(['Oto Brglez', 'Ota Novak', 'Tomaž Ota'])
    expect(names('ota')).toEqual(['Ota Novak', 'Tomaž Ota'])
    expect(names('ma')).toEqual(['Maja Kovač', 'dr. Matej Čuhalev'])
  })

  it('offers no one for nothing typed, and at most as many as asked', () => {
    expect(names('')).toEqual([])
    expect(names('  ·  ')).toEqual([])
    expect(names('ot', 2)).toEqual(['Oto Brglez', 'Ota Novak'])
  })
})
