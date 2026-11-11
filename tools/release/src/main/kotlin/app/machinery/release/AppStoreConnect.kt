package app.machinery.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** The App Store Connect API as the App Store tool uses it: JSON:API documents in and out. */
interface AppStoreApi {
    /** The document at [path] (from the API's root, with its query), or null when the resource doesn't exist. */
    fun get(path: String): JsonObject?

    /** Every resource of the collection at [path], through all its pages. */
    fun getAll(path: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var next: String? = path
        while (next != null) {
            val page = get(next) ?: break
            all += page["data"]?.jsonArray.orEmpty().map { it.jsonObject }
            next =
                page["links"]
                    ?.jsonObject
                    ?.get("next")
                    ?.jsonPrimitive
                    ?.contentOrNull
        }
        return all
    }

    /** Creates the resource [body] describes in the collection at [path], and returns its document. */
    fun post(
        path: String,
        body: JsonObject,
    ): JsonObject

    /** Changes the resource at [path] as [body] says. */
    fun patch(
        path: String,
        body: JsonObject,
    )

    /** Deletes the resource at [path]. */
    fun delete(path: String)

    /** Sends one part of a file, as an upload operation App Store Connect handed out asks. */
    fun upload(
        operation: UploadOperation,
        bytes: ByteArray,
    )
}

/** One part of a file to send: [length] bytes from [offset], with [method] to [url] and its [headers]. */
data class UploadOperation(
    val method: String,
    val url: String,
    val offset: Int,
    val length: Int,
    val headers: Map<String, String>,
) {
    companion object {
        /** The operations of a resource's `uploadOperations` attribute. */
        fun of(json: JsonElement?): List<UploadOperation> =
            json?.jsonArray.orEmpty().map { element ->
                val operation = element.jsonObject
                UploadOperation(
                    method = operation.string("method") ?: "PUT",
                    url = checkNotNull(operation.string("url")) { "An upload operation without a URL" },
                    offset = operation.int("offset"),
                    length = operation.int("length"),
                    headers =
                        operation["requestHeaders"]?.jsonArray.orEmpty().associate {
                            val header = it.jsonObject
                            header.string("name").orEmpty() to header.string("value").orEmpty()
                        },
                )
            }

        private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
    }
}

/**
 * App Store Connect's API (at [host], Apple's own unless a test says), signed in with a team API key: its id
 * [keyId], its issuer [issuerId] and its private [key]. Every request carries a token of a few minutes that the key
 * signs.
 */
class AppStoreConnect(
    private val keyId: String,
    private val issuerId: String,
    private val key: PrivateKey,
    private val clock: Clock = Clock.systemUTC(),
    private val host: String = HOST,
    private val retryAfter: Duration = RETRY,
) : AppStoreApi {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()
    private var token: String? = null
    private var signedAt: Instant = Instant.EPOCH

    override fun get(path: String): JsonObject? {
        val response = send("GET", path, null)
        return if (response.statusCode() == NOT_FOUND) null else parse(response.body())
    }

    override fun post(
        path: String,
        body: JsonObject,
    ): JsonObject = parse(send("POST", path, body).body()) ?: JsonObject(emptyMap())

    override fun patch(
        path: String,
        body: JsonObject,
    ) {
        send("PATCH", path, body)
    }

    override fun delete(path: String) {
        send("DELETE", path, null)
    }

    override fun upload(
        operation: UploadOperation,
        bytes: ByteArray,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create(operation.url))
                .timeout(Duration.ofSeconds(TIMEOUT_S))
                .method(operation.method, HttpRequest.BodyPublishers.ofByteArray(bytes))
        // Java sets the ones about the connection itself, and refuses them from callers
        operation.headers
            .filterKeys { it.lowercase() !in RESTRICTED_HEADERS }
            .forEach { (name, value) -> request.header(name, value) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in OK) {
            throw IOException("The upload of a screenshot's part was answered ${response.statusCode()}")
        }
    }

    /** Sends a request, trying again a few times when Apple is busy (429) or failing (5xx) for a moment. */
    private fun send(
        method: String,
        path: String,
        body: JsonObject?,
    ): HttpResponse<String> {
        val url = url(host, path)
        var attempt = 0
        while (true) {
            val response = client.send(request(method, url, body), HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val retry = status == TOO_MANY_REQUESTS || (status >= SERVER_ERROR && method == "GET")
            when {
                status in OK || (status == NOT_FOUND && method == "GET") -> return response
                retry && ++attempt < ATTEMPTS -> Thread.sleep(retryAfter.toMillis() * attempt)
                else -> throw IOException(failure(method, URI.create(url).path, status, response.body()))
            }
        }
    }

    private fun request(
        method: String,
        url: String,
        body: JsonObject?,
    ): HttpRequest {
        val publisher =
            body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        return HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_S))
            .header("Authorization", "Bearer ${currentToken()}")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
    }

    /** A token signed in the last few minutes: Apple accepts them for 20. */
    private fun currentToken(): String {
        val now = clock.instant()
        val current = token
        if (current != null && Duration.between(signedAt, now) < RESIGN_AFTER) return current
        signedAt = now
        return AppStoreToken.sign(keyId, issuerId, key, now).also { token = it }
    }

    companion object {
        /**
         * The URL of [path], from the API's root unless it is whole already (a page's `next` link), with the brackets
         * of its filters escaped, which Java's URIs don't take as they are.
         */
        fun url(
            host: String,
            path: String,
        ): String = (if (path.startsWith("http")) path else "$host$path").replace("[", "%5B").replace("]", "%5D")

        /** The API signed in with the key of `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (the .p8 file). */
        fun fromEnvironment(): AppStoreConnect =
            AppStoreConnect(
                keyId = environment("ASC_KEY_ID"),
                issuerId = environment("ASC_ISSUER_ID"),
                key = AppStoreToken.key(environment("ASC_PRIVATE_KEY")),
            )

        /** What went wrong, from Apple's errors: their codes, titles and details, never the request's own data. */
        fun failure(
            method: String,
            path: String,
            status: Int,
            body: String,
        ): String {
            val errors =
                runCatching { Json.parseToJsonElement(body).jsonObject["errors"]?.jsonArray }.getOrNull().orEmpty()
            val reasons =
                errors.joinToString("\n") { error ->
                    val e = error.jsonObject
                    listOfNotNull(e.string("code"), e.string("title"), e.string("detail")).joinToString(": ")
                }
            val hint = if (status == FORBIDDEN) FORBIDDEN_HINT else ""
            return "App Store Connect answered $status to $method $path$hint" +
                (if (reasons.isNotEmpty()) "\n$reasons" else "\n${body.take(ERROR_BODY_CHARS)}")
        }

        private fun parse(body: String): JsonObject? =
            body
                .takeIf {
                    it.isNotBlank()
                }?.let { Json.parseToJsonElement(it).jsonObject }

        private fun environment(name: String): String =
            checkNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "$name is not set" }

        private const val HOST = "https://api.appstoreconnect.apple.com"
        private const val TIMEOUT_S = 120L
        private const val ATTEMPTS = 4
        private val RETRY: Duration = Duration.ofSeconds(5)
        private val RESTRICTED_HEADERS = setOf("content-length", "host", "connection", "expect", "upgrade")
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500
        private const val ERROR_BODY_CHARS = 500
        private val OK = 200..299
        private val RESIGN_AFTER = Duration.ofMinutes(10)
        private const val FORBIDDEN_HINT =
            " (the API key needs the Admin role: App Store Connect → Users and Access → Integrations)"
    }
}

/** The tokens App Store Connect's API takes: JSON Web Tokens signed with ES256 by a team key. */
object AppStoreToken {
    /** A token of [keyId] for [issuerId], valid from [now] for 19 minutes (Apple's limit is 20). */
    fun sign(
        keyId: String,
        issuerId: String,
        key: PrivateKey,
        now: Instant,
    ): String {
        val header =
            buildJsonObject {
                put("alg", "ES256")
                put("kid", keyId)
                put("typ", "JWT")
            }
        val payload =
            buildJsonObject {
                put("iss", issuerId)
                put("iat", now.epochSecond)
                put("exp", now.epochSecond + LIFETIME_S)
                put("aud", "appstoreconnect-v1")
            }
        val input = "${base64(header.toString().toByteArray())}.${base64(payload.toString().toByteArray())}"
        // JWTs want the signature's two numbers side by side (IEEE P1363), not Java's default DER sequence
        val signature =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initSign(key)
                update(input.toByteArray())
                sign()
            }
        return "$input.${base64(signature)}"
    }

    /** The private key of a .p8 file's [pem] text (PKCS #8, elliptic curve), with or without its header lines. */
    fun key(pem: String): PrivateKey {
        val body =
            pem
                .replace("\\n", "\n")
                .lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .filterNot(Char::isWhitespace)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val LIFETIME_S = 19 * 60L
}

/** The `id` of a JSON:API resource. */
val JsonObject.id: String get() = checkNotNull(string("id")) { "A resource without an id" }

/** The `attributes` of a JSON:API resource, empty when it has none. */
val JsonObject.attributes: JsonObject get() = this["attributes"] as? JsonObject ?: JsonObject(emptyMap())

/** The text of the member [name], or null when it is missing or null. */
fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** The id a to-one relationship [name] of a resource points to, when the response says it. */
fun JsonObject.related(name: String): String? =
    (
        (this["relationships"] as? JsonObject)?.get(
            name,
        ) as? JsonObject
    )?.get("data")?.let { it as? JsonObject }?.string("id")

/** The `data` of a document, as one resource, or null when it is missing or null. */
val JsonObject.resource: JsonObject? get() = this["data"] as? JsonObject

/** The `data` of a document, as a list of resources. */
val JsonObject.resources: List<JsonObject> get() = (this["data"] as? JsonArray).orEmpty().map { it.jsonObject }

/** The resources of a document's `included` list of [type]. */
fun JsonObject.included(type: String): List<JsonObject> =
    (this["included"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.string("type") == type }
