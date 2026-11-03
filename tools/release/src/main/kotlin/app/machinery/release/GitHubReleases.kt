package app.machinery.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A release on GitHub: its [version] (the tag without its `v`) and the Markdown [changelog] it shows. */
data class PublishedRelease(
    val version: String,
    val changelog: String,
)

/**
 * The releases of the GitHub repository [repository] (`owner/name`), through the REST API at [api], with [token]
 * when the repository is private.
 */
class GitHubReleases(
    private val api: String,
    private val repository: String,
    private val token: String?,
) {
    /** The latest releases, not drafts or pre-releases, newest version first. */
    fun published(): List<PublishedRelease> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$api/repos/$repository/releases?per_page=$PAGE"))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(TIMEOUT_S))
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
        val response =
            HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        if (response.statusCode() !in OK) {
            throw IOException("GitHub answered ${response.statusCode()}: ${response.body().take(ERROR_BODY_CHARS)}")
        }
        return parse(response.body())
    }

    companion object {
        /** More releases than the notes ever have room for. */
        private const val PAGE = 30
        private const val TIMEOUT_S = 60L
        private const val ERROR_BODY_CHARS = 500
        private val OK = 200..299

        /** The published releases a `GET /repos/{owner}/{repo}/releases` response lists, newest version first. */
        fun parse(json: String): List<PublishedRelease> =
            Json
                .parseToJsonElement(json)
                .jsonArray
                .map { it as JsonObject }
                .filterNot { it.flag("draft") || it.flag("prerelease") }
                .mapNotNull { release ->
                    val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PublishedRelease(tag.removePrefix("v"), release["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }.sortedWith(compareBy(Versions.NEWEST_FIRST) { it.version })

        private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.booleanOrNull == true
    }
}
