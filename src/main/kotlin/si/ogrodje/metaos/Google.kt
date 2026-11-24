package si.ogrodje.metaos

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import java.io.IOException

/*
 * MetaOS uses Google as Ogrodje's bot account, through the "Desktop app" OAuth client "MetaOS" of Google Cloud project
 * ogrodje-metaos. Drive, Gmail and YouTube have a refresh token each (google/refresh_token.sh): Google revokes tokens
 * with Gmail access when the account's password changes, which shouldn't take the others down with it; and YouTube's is
 * for the bot as the channel, which it picks as it signs in.
 */

val googleTransport = NetHttpTransport()
val googleJson: GsonFactory = GsonFactory.getDefaultInstance()

/**
 * A refresh token of the bot account. The library turns it into access tokens; [call] says what to do when Google stops
 * accepting it - the bot removed MetaOS's access, or the token went unused for six months.
 */
class GoogleAccount(clientId: String, clientSecret: String, refreshToken: String, private val renew: String) {

    private val credentials = UserCredentials.newBuilder()
        .setClientId(clientId).setClientSecret(clientSecret).setRefreshToken(refreshToken)
        .build()

    /** Signs a Google API client's requests, and gives them timeouts */
    val requests = HttpRequestInitializer { request ->
        HttpCredentialsAdapter(credentials).initialize(request)
        request.connectTimeout = 10_000
        request.readTimeout = 30_000
    }

    /** An access token of the bot's, for an API MetaOS calls itself (YouTube.kt); a new one once the last is about to expire */
    fun accessToken(): String = call {
        credentials.refreshIfExpired()
        checkNotNull(credentials.accessToken) { "Google gave no access token" }.tokenValue
    }

    fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if ("invalid_grant" in e.message.orEmpty()) throw IllegalStateException("Google no longer accepts the bot's refresh token. $renew", e)
        throw e
    }
}
