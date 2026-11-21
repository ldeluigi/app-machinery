package app.machinery.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * An edit of the app [packageName] on Google Play, through the Google Play Developer API (v3) with an OAuth access
 * [token] for its `androidpublisher` scope. Nothing it changes shows until [commit]; [discard] throws it away. It
 * reaches the store listing's graphics in [language], the default one unless given, and every language's texts.
 */
class PlayEdit private constructor(
    packageName: String,
    private val token: String,
    private val language: String,
) : ListingImages,
    ListingTexts {
    private val requests = GoogleRequests("Google Play", FORBIDDEN_HINT)
    private val edit =
        "applications/$packageName/edits".let { edits ->
            "$edits/" + editId(send(request(edits).POST(HttpRequest.BodyPublishers.ofString("{}"))))
        }

    override fun hashes(type: ImageType): List<String> = imageHashes(send(request(images(type)).GET()))

    override fun replace(
        type: ImageType,
        files: List<File>,
    ) {
        send(request(images(type)).DELETE())
        files.forEach { file ->
            send(
                request(images(type), upload = true)
                    .header("Content-Type", "image/png")
                    .POST(HttpRequest.BodyPublishers.ofFile(file.toPath())),
            )
        }
    }

    override fun texts(): Map<String, PlayTexts> = listingTexts(send(request("$edit/listings").GET()))

    override fun write(
        language: String,
        texts: PlayTexts,
        new: Boolean,
    ) {
        val body =
            buildJsonObject {
                put("language", language)
                put("title", texts.title)
                put("shortDescription", texts.shortDescription)
                put("fullDescription", texts.fullDescription)
            }.toString()
        // PUT replaces the whole listing, its promo video too: an existing one is patched instead
        send(
            request("$edit/listings/$language")
                .header("Content-Type", "application/json")
                .method(if (new) "PUT" else "PATCH", HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    /**
     * Publishes the edit's changes, and returns whether Play sent them to Google's review. When Play won't send them
     * itself ([holdsForReview]), they are published as changes waiting for the owner to send them for review in Play
     * Console, and it returns false; if Play refuses that too, its second answer is the error, with the first.
     */
    fun commit(): Boolean =
        try {
            send(request("$edit:commit").POST(HttpRequest.BodyPublishers.noBody()))
            true
        } catch (refused: GoogleApiException) {
            if (!holdsForReview(refused)) throw refused
            try {
                send(request("$edit:commit?changesNotSentForReview=true").POST(HttpRequest.BodyPublishers.noBody()))
            } catch (again: GoogleApiException) {
                throw again.apply { addSuppressed(refused) }
            }
            false
        }

    /** Throws the edit away, with its changes. */
    fun discard() {
        send(request(edit).DELETE())
    }

    private fun images(type: ImageType) = "$edit/listings/$language/${type.apiName}"

    /** A request for [path] of the API, or of its media upload endpoint when [upload]. */
    private fun request(
        path: String,
        upload: Boolean = false,
    ): HttpRequest.Builder {
        val url = if (upload) "$HOST/upload/$API/$path?uploadType=media" else "$HOST/$API/$path"
        return HttpRequest
            .newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(TIMEOUT_S))
    }

    private fun send(builder: HttpRequest.Builder): String = requests.send(builder)

    companion object {
        /** The language of the app's default store listing, the one Play falls back on. */
        const val DEFAULT_LANGUAGE = "en-US"

        /** Opens a new edit of the app [packageName]. */
        fun open(
            packageName: String,
            token: String,
            language: String = DEFAULT_LANGUAGE,
        ): PlayEdit = PlayEdit(packageName, token, language)

        /** The id of the edit an `edits.insert` response describes. */
        fun editId(json: String): String =
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content

        /** Each language's texts in an `edits.listings.list` response; a missing text is empty. */
        fun listingTexts(json: String): Map<String, PlayTexts> =
            Json
                .parseToJsonElement(json)
                .jsonObject["listings"]
                ?.jsonArray
                ?.associate { element ->
                    val listing = element.jsonObject
                    val text = { key: String -> listing[key]?.jsonPrimitive?.contentOrNull.orEmpty() }
                    text("language") to PlayTexts(text("title"), text("shortDescription"), text("fullDescription"))
                }.orEmpty()

        /** The SHA-256 of each image an `edits.images.list` response lists; it lists nothing when there are none. */
        fun imageHashes(json: String): List<String> =
            Json
                .parseToJsonElement(json)
                .jsonObject["images"]
                ?.jsonArray
                ?.map {
                    it.jsonObject
                        .getValue("sha256")
                        .jsonPrimitive.content
                }.orEmpty()

        /**
         * Whether Play refused to commit an edit because it won't send its changes to review by itself, so they can
         * only wait for the owner to send them: a 400 that asks for `changesNotSentForReview`, as when the app's
         * changes wait for the owner to publish them; or a 403, as Play answers for an app it never reviewed, even
         * when the service account has every permission.
         */
        fun holdsForReview(refused: GoogleApiException): Boolean =
            refused.status == FORBIDDEN ||
                (refused.status == BAD_REQUEST && "changesNotSentForReview" in refused.body)

        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
        private const val HOST = "https://androidpublisher.googleapis.com"
        private const val API = "androidpublisher/v3"
        private const val TIMEOUT_S = 120L
        private const val FORBIDDEN_HINT =
            " (the service account needs Manage store presence on the app: Play Console → Users and permissions;" +
                " Play also refuses to commit the listing of an app it never reviewed, until its first release is" +
                " sent for review in Play Console)"
    }
}
