import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import Keycloak, { type KeycloakConfig } from 'keycloak-js'
import {
  accountConsoleUrl, linkAction, linkedAlias, type LinkOutcome, linkOutcome, loadSignInProviders, providerName,
  type SignInProvider,
} from '@/accounts'

/** Realm metaos's identity providers (infra/keycloak/realm/identity_providers.tf) */
export type IdentityProvider = 'discord' | 'google'

/** Where users sign in, from the runtime config (nuxt.config.ts), so one build works with every Keycloak */
export interface SignInConfig {
  issuer: string
  clientId: string
}

/**
 * The realm role of an approved user, Ogrodje's team (APPROVED_ROLE in Security.kt): only they get the team's data from
 * the API. Anyone else who signs in sees what everyone does.
 */
const TEAM_ROLE = 'metaos-user'

// How long the sign-in check may take before the page carries on with a guest: longer for one who was signed in here
// last time, who's most likely still signed in and would otherwise see a guest's page while Keycloak is slow
const SIGN_IN_CHECK_TIMEOUT = 5000
const MEMBER_CHECK_TIMEOUT = 20000

// Whether the user was on the team on this browser last time: only a hint for what to show while it's checked, never
// access, which takes a token. Storage can be unavailable, e.g. in a private window.
const SIGNED_IN_KEY = 'metaos.signedIn'

function wasSignedIn() {
  try {
    return localStorage.getItem(SIGNED_IN_KEY) === 'true'
  } catch {
    return false
  }
}

function rememberSignedIn(signedIn: boolean) {
  try {
    if (signedIn) localStorage.setItem(SIGNED_IN_KEY, 'true')
    else localStorage.removeItem(SIGNED_IN_KEY)
  } catch {
    // Without storage, every load waits as for a guest
  }
}

// The identity provider being linked while Keycloak has the page, so the page can name it once it's back
const LINKING = 'metaos.linking'

type Linking = Pick<SignInProvider, 'alias' | 'name'>

// In the browser's session storage, which may be unavailable, e.g. in a private window: the outcome then names the
// provider by its alias
function rememberLinking(provider: Linking) {
  try {
    sessionStorage.setItem(LINKING, JSON.stringify({ alias: provider.alias, name: provider.name }))
  } catch {
    // Named by its alias instead
  }
}

// Once: a later load isn't back from linking it
function recallLinking(): Linking | undefined {
  try {
    const saved = sessionStorage.getItem(LINKING)
    sessionStorage.removeItem(LINKING)
    const linking: unknown = saved ? JSON.parse(saved) : undefined
    if (typeof linking !== 'object' || linking === null) return undefined
    const { alias, name } = linking as Record<string, unknown>
    return typeof alias === 'string' && typeof name === 'string' ? { alias, name } : undefined
  } catch {
    return undefined
  }
}

// What Keycloak said went wrong, when it sent an error back instead of a sign-in (KeycloakError)
function errorDescription(error: unknown) {
  if (typeof error !== 'object' || error === null) return null
  const description = (error as Record<string, unknown>).error_description
  return typeof description === 'string' && description ? description : null
}

/**
 * The signed-in user, through Keycloak's client metaos-ui. Only in the browser: the server renders pages as someone who
 * isn't signed in sees them, and plugins/auth.client.ts starts this once the page is hydrated.
 */
export const useAuthStore = defineStore('auth', () => {
  let keycloak: Keycloak | undefined
  // Whether it's known yet if the user is signed in
  const ready = ref(false)
  const authenticated = ref(false)
  // Signed in as an approved user, who gets the team's data
  const team = ref(false)
  const username = ref<string>()
  // The realm, e.g. https://metaos-auth.ogrodje.si/realms/metaos
  const issuer = ref<string>()
  // The identity provider this sign-in came through, e.g. google, when Keycloak puts it in the token (a User Session
  // Note mapper of identity_provider); undefined otherwise, also for Keycloak's own login, e.g. dev's
  const identityProvider = ref<string>()
  // How linking an identity provider went, once the page is back from Keycloak, until the user has seen it
  const linked = ref<LinkOutcome>()

  /** Where users link and unlink identity providers themselves, in Keycloak's Account Console */
  const accountConsole = computed(() => (issuer.value ? accountConsoleUrl(issuer.value) : undefined))

  // Was on the team here last time, known once the page is hydrated, as the server can't know
  const remembered = ref(false)
  // On the team, or most likely so while it's checked: the header keeps the team's tabs, rather than dropping them and
  // bringing them back once Keycloak answers
  const likelyMember = computed(() => team.value || (!ready.value && remembered.value))

  // What the token says, after signing in and each refresh, which may come with an approval given or taken back
  function update(client: Keycloak) {
    authenticated.value = !!client.authenticated
    team.value = !!client.authenticated && client.hasRealmRole(TEAM_ROLE)
    username.value = client.tokenParsed?.preferred_username
    const provider = client.tokenParsed?.identity_provider
    identityProvider.value = typeof provider === 'string' && provider ? provider : undefined
    rememberSignedIn(team.value)
  }

  async function init(config: SignInConfig) {
    remembered.value = wasSignedIn()
    try {
      // An issuer is <Keycloak's URL>/realms/<realm>
      const [, url, realm] = config.issuer.match(/^(.+)\/realms\/([^/]+)$/) ?? []
      if (!url || !realm) throw new Error(`Unexpected issuer ${config.issuer}`)
      issuer.value = config.issuer

      const checked = start({ url, realm, clientId: config.clientId }, recallLinking())
      // keycloak-js waits for the iframe for ever when Keycloak doesn't answer, e.g. when it's down. Until it does,
      // the user is a guest, who can still try to sign in; should it answer later, they're signed in then.
      const timeout = remembered.value ? MEMBER_CHECK_TIMEOUT : SIGN_IN_CHECK_TIMEOUT
      await Promise.race([checked, new Promise((resolve) => setTimeout(resolve, timeout))])
    } catch (error) {
      console.error('Signing in is unavailable', error)
    } finally {
      ready.value = true
    }
  }

  // Signed in already? Also back from Keycloak with how an action went, e.g. linking Discord, which keycloak-js tells
  // onActionUpdate before it signs the user in
  async function start(config: KeycloakConfig, linking?: Linking): Promise<void> {
    const client = new Keycloak(config)
    keycloak = client
    let action: { status: string, name?: string } | undefined
    client.onActionUpdate = (status, name) => {
      action = { status, name }
    }
    try {
      await client.init({
        // Asked in a hidden iframe (public/silent-check-sso.html), so the page stays where it is
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
        pkceMethod: 'S256',
        // A sign-out elsewhere shows when the token can't be refreshed any more
        checkLoginIframe: false,
      })
      update(client)
      // Which action it was, when Keycloak says (kc_action); otherwise the one this page started
      const alias = action?.name ? linkedAlias(action.name) : linking?.alias
      if (action && alias) linked.value = linkOutcome(action.status, nameOf(alias, linking))
    } catch (error) {
      // Keycloak may answer a link with an error instead, which keycloak-js takes for a failed sign-in and throws. The
      // user is still signed in to Keycloak, so a new client asks again, once; the address no longer has the answer.
      if (linking) {
        const detail = errorDescription(error)
        const status = action?.status ?? (/cancel/i.test(detail ?? '') ? 'cancelled' : 'error')
        linked.value = linkOutcome(status, linking.name, status === 'error' ? detail : null)
        return start(config)
      }
      console.error('Signing in is unavailable', error)
    }
  }

  // Its name as the menu had it when it was linked, when it's the same provider
  function nameOf(alias: string, linking?: Linking) {
    return linking?.alias === alias ? linking.name : providerName(alias)
  }

  /** Straight to the identity provider, past Keycloak's own login page, and back to this page */
  function login(idpHint: IdentityProvider) {
    keycloak?.login({ idpHint })
  }

  function logout() {
    rememberSignedIn(false)
    keycloak?.logout({ redirectUri: `${location.origin}/` })
  }

  /**
   * Links another identity provider, e.g. Discord, to the signed-in user's Keycloak user: Keycloak's
   * application-initiated action idp_link, after which the page is back here and says how it went (linked). Their
   * Keycloak user, and so their account in MetaOS, stays the same.
   */
  function linkProvider(provider: Linking) {
    if (!keycloak) return
    rememberLinking(provider)
    // This page, without its #, which keycloak-js puts its answer in
    keycloak.login({ action: linkAction(provider.alias), redirectUri: location.href.replace(/#.*$/, '') })
      .catch((error) => {
        recallLinking()
        linked.value = linkOutcome('error', provider.name, error instanceof Error ? error.message : null)
      })
  }

  /** The realm's identity providers, and which the signed-in user signs in with, from Keycloak's Account REST API */
  async function signInProviders(): Promise<SignInProvider[]> {
    const current = await token()
    if (!current || !issuer.value) throw new Error('Not signed in')
    return loadSignInProviders(issuer.value, current)
  }

  /** The user has seen how linking went */
  function dismissLinked() {
    linked.value = undefined
  }

  /**
   * An access token for MetaOS's API, refreshed when it's about to expire; none when signed out. When Keycloak can't be
   * asked, e.g. right after waking up offline or while it restarts, the user stays signed in and this fails instead.
   */
  async function token(): Promise<string | undefined> {
    if (!keycloak?.authenticated) return undefined
    try {
      if (await keycloak.updateToken(30)) update(keycloak)
    } catch (error) {
      // keycloak-js drops the tokens only when Keycloak refuses the refresh token: the Keycloak session is over
      if (!keycloak.authenticated) {
        update(keycloak)
        return undefined
      }
      throw new Error("Couldn't refresh the sign-in with Keycloak; try again in a moment", { cause: error })
    }
    return keycloak.token
  }

  return {
    ready, authenticated, team, likelyMember, username, identityProvider, accountConsole, linked,
    init, login, logout, linkProvider, signInProviders, dismissLinked, token,
  }
})
