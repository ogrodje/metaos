import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import Keycloak from 'keycloak-js'

/** Realm metaos's identity providers (keycloak/discord_idp.sh, keycloak/google_idp.sh) */
export type IdentityProvider = 'discord' | 'google'

/** Where users sign in, from MetaOS's /config.json (Frontend.kt), so one build works with every Keycloak */
interface FrontendConfig {
  issuer: string
  clientId: string
}

// How long the sign-in check may take before the page carries on with a guest: longer for one who was signed in here
// last time, who's most likely still signed in and would otherwise see a guest's page while Keycloak is slow
const SIGN_IN_CHECK_TIMEOUT = 5000
const MEMBER_CHECK_TIMEOUT = 20000

// Whether the user was signed in on this browser last time: only a hint for what to show while it's checked, never
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

/** The signed-in user, through Keycloak's client metaos-ui */
export const useAuthStore = defineStore('auth', () => {
  let keycloak: Keycloak | undefined
  // Whether it's known yet if the user is signed in
  const ready = ref(false)
  const authenticated = ref(false)
  const username = ref<string>()
  const remembered = wasSignedIn()
  // Signed in, or most likely so while it's checked: the header keeps the team's tabs, rather than dropping them and
  // bringing them back once Keycloak answers
  const likelyMember = computed(() => authenticated.value || (!ready.value && remembered))

  async function init() {
    try {
      const config: FrontendConfig = await (await fetch('/config.json')).json()
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
      }).then((signedIn) => {
        authenticated.value = signedIn
        username.value = client.tokenParsed?.preferred_username
        rememberSignedIn(signedIn)
      }).catch((error) => console.error('Signing in is unavailable', error))
      // keycloak-js waits for the iframe for ever when Keycloak doesn't answer, e.g. when it's down. Until it does,
      // the user is a guest, who can still try to sign in; should it answer later, they're signed in then.
      const timeout = remembered ? MEMBER_CHECK_TIMEOUT : SIGN_IN_CHECK_TIMEOUT
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
      await keycloak.updateToken(30)
    } catch (error) {
      // keycloak-js drops the tokens only when Keycloak refuses the refresh token: the Keycloak session is over
      if (!keycloak.authenticated) {
        authenticated.value = false
        rememberSignedIn(false)
        return undefined
      }
      throw new Error("Couldn't refresh the sign-in with Keycloak; try again in a moment", { cause: error })
    }
    return keycloak.token
  }

  return { ready, authenticated, likelyMember, username, init, login, logout, token }
})
