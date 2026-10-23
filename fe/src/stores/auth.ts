import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import Keycloak from 'keycloak-js'

/** Realm metaos's identity providers (keycloak/discord_idp.sh, keycloak/google_idp.sh) */
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
    rememberSignedIn(team.value)
  }

  async function init(config: SignInConfig) {
    remembered.value = wasSignedIn()
    try {
      // An issuer is <Keycloak's URL>/realms/<realm>
      const [, url, realm] = config.issuer.match(/^(.+)\/realms\/([^/]+)$/) ?? []
      if (!url || !realm) throw new Error(`Unexpected issuer ${config.issuer}`)

      const client = new Keycloak({ url, realm, clientId: config.clientId })
      keycloak = client
      const checked = client.init({
        // Signed in already? Asked in a hidden iframe (public/silent-check-sso.html), so the page stays where it is
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
        pkceMethod: 'S256',
        // A sign-out elsewhere shows when the token can't be refreshed any more
        checkLoginIframe: false,
      }).then(() => update(client)).catch((error) => console.error('Signing in is unavailable', error))
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

  /** Straight to the identity provider, past Keycloak's own login page, and back to this page */
  function login(idpHint: IdentityProvider) {
    keycloak?.login({ idpHint })
  }

  function logout() {
    rememberSignedIn(false)
    keycloak?.logout({ redirectUri: `${location.origin}/` })
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

  return { ready, authenticated, team, likelyMember, username, init, login, logout, token }
})
