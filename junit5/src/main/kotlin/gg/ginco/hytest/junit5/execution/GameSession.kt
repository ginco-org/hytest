package gg.ginco.hytest.junit5.execution

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Creates a Hytale game session so the hytest-launched server starts fully
 * authenticated (--session-token / --identity-token) — no interactive /auth.
 *
 * Mirrors hygradle's RunServerTask session-auth flow. Uses only an
 * already-cached `auth:server` token from hygradle's cache — never refreshes
 * and never triggers an interactive device-code flow, which would block a
 * headless test run. Fail-soft: any problem returns null and the server
 * simply starts without session tokens.
 */
object GameSession {

    data class Tokens(val sessionToken: String, val identityToken: String)

    private const val ACCOUNT_DATA_BASE = "https://account-data.hytale.com"
    private const val SESSIONS_BASE = "https://sessions.hytale.com"

    // The sessions/account-data APIs verify launcher identification headers.
    private const val LAUNCHER_VERSION = "2026.05.29-125f35f"
    private const val LAUNCHER_USER_AGENT = "hytale-launcher/$LAUNCHER_VERSION"
    private const val LAUNCHER_BRANCH = "release"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun resolve(): Tokens? = try {
        val accessToken = cachedAccessToken()
        if (accessToken == null) {
            println("[hytest] no cached auth:server token — server starts without session tokens")
            null
        } else resolve(accessToken)
    } catch (e: Exception) {
        println("[hytest] session token resolution failed (${e.message}) — server starts without session tokens")
        null
    }

    private fun resolve(accessToken: String): Tokens {
        val profile = firstProfile(accessToken)
        println("[hytest] creating game session for profile '${profile.second}'...")

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$SESSIONS_BASE/game-session/new"))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", LAUNCHER_USER_AGENT)
            .header("X-Hytale-Launcher-Version", LAUNCHER_VERSION)
            .header("X-Hytale-Launcher-Branch", LAUNCHER_BRANCH)
            .POST(HttpRequest.BodyPublishers.ofString("""{"uuid":"${profile.first}"}"""))
            .build()
        val body = send(request, "game-session/new")
        val sessionToken = jsonField(body, "sessionToken")
            ?: throw IllegalStateException("session response missing 'sessionToken'")
        val identityToken = jsonField(body, "identityToken")
            ?: throw IllegalStateException("session response missing 'identityToken'")
        return Tokens(sessionToken, identityToken)
    }

    /** Returns (uuid, username) of the account's first game profile. */
    private fun firstProfile(accessToken: String): Pair<String, String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ACCOUNT_DATA_BASE/my-account/get-profiles"))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", LAUNCHER_USER_AGENT)
            .header("X-Hytale-Launcher-Version", LAUNCHER_VERSION)
            .header("X-Hytale-Launcher-Branch", LAUNCHER_BRANCH)
            .GET()
            .build()
        val body = send(request, "get-profiles")
        val uuid = jsonField(body, "uuid")
            ?: throw IllegalStateException("profiles response missing 'uuid'")
        val username = jsonField(body, "username") ?: uuid
        return uuid to username
    }

    private fun send(request: HttpRequest, what: String): String {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("$what failed: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /** Reads hygradle's cached `auth:server` token without any network I/O. */
    private fun cachedAccessToken(): String? {
        val path = Path.of(System.getProperty("user.home"), ".local", "share", "hygradle", "token-server.json")
        if (!Files.isRegularFile(path)) return null
        val text = Files.readString(path)
        val token = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
        val expiresAt = Regex("\"expires_at\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: return null
        if (expiresAt - System.currentTimeMillis() < 60_000) return null
        return token
    }

    /** First string value for [key] anywhere in the JSON document. */
    private fun jsonField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
}
