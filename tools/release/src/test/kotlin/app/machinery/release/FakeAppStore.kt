package app.machinery.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** App Store Connect in memory: canned documents for the reads, a record of every call. */
class FakeAppStore : AppStoreApi {
    /** Every call, as "METHOD path", with its body. */
    val calls = mutableListOf<Pair<String, JsonObject?>>()

    /** The document each path reads; a path mapped to null doesn't exist (404). */
    val documents = mutableMapOf<String, JsonObject?>()

    /** What creating a resource at a path answers. */
    val created = mutableMapOf<String, JsonObject>()

    /** The calls that fail, as "METHOD path". */
    val failing = mutableSetOf<String>()

    /** The parts uploaded, by URL. */
    val uploads = mutableMapOf<String, ByteArray>()

    /** The writes: every call but the reads. */
    val writes: List<String> get() = calls.map { it.first }.filterNot { it.startsWith("GET ") }

    /** The body of the only call [call]. */
    fun body(call: String): JsonObject = checkNotNull(calls.single { it.first == call }.second)

    override fun get(path: String): JsonObject? {
        record("GET $path", null)
        check(path in documents) { "Unexpected GET $path" }
        return documents[path]
    }

    override fun post(
        path: String,
        body: JsonObject,
    ): JsonObject {
        record("POST $path", body)
        return created[path] ?: JsonObject(emptyMap())
    }

    override fun patch(
        path: String,
        body: JsonObject,
    ) = record("PATCH $path", body)

    override fun delete(path: String) = record("DELETE $path", null)

    override fun upload(
        operation: UploadOperation,
        bytes: ByteArray,
    ) {
        record("UPLOAD ${operation.url}", null)
        uploads[operation.url] = bytes
    }

    private fun record(
        call: String,
        body: JsonObject?,
    ) {
        calls += call to body
        check(call !in failing) { "App Store Connect answered 409 to $call" }
    }
}

/** A JSON object written as JSON text. */
fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** A document of one resource, or of a list of them. */
fun document(vararg resources: String): JsonObject = json("""{"data":[${resources.joinToString(",")}]}""")

/** A document of a single resource. */
fun single(resource: String): JsonObject = json("""{"data":$resource}""")

/** A listing folder with an English and a German text, its settings and its notes for App Review. */
fun listingIn(dir: File): File {
    fun write(
        name: String,
        text: String,
    ) = dir.resolve(name).apply { parentFile.mkdirs() }.writeText(text)
    write("en-US/name.txt", "{app.name}\n")
    write("en-US/subtitle.txt", "A game for friends")
    write("en-US/description.txt", "{app.name} is a party game.\n\nPlay it.\n")
    write("en-US/keywords.txt", "party,friends")
    write("en-US/promotional_text.txt", "Now with stars.")
    write("en-US/beta_description.txt", "Test builds of {app.name}.")
    write("de-DE/name.txt", "{app.name}")
    write("de-DE/description.txt", "{app.name} ist ein Partyspiel.")
    write("review-notes.txt", "No account needed.")
    write(
        AppStoreListing.SETTINGS,
        """
        # A comment
        primaryCategory=GAMES
        primarySubcategoryOne=GAMES_PUZZLE
        contentRightsDeclaration=USES_THIRD_PARTY_CONTENT
        supportUrl=https://example.com/support
        privacyPolicyUrl=https://example.com/privacy
        releaseType=MANUAL
        price=0
        availability.newTerritories=true
        availability.exclude=CHN
        ageRating.profanityOrCrudeHumor=INFREQUENT
        ageRating.unrestrictedWebAccess=false
        review.demoAccountRequired=false
        review.contactEmail=support@example.com
        testflight.feedbackEmail=support@example.com
        testflight.internalGroup=Team
        testflight.internalTesterRoles=ACCOUNT_HOLDER,ADMIN
        """.trimIndent(),
    )
    return dir
}
