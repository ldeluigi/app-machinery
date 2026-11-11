package app.machinery.release

import java.io.File

/** The texts of a Google Play store listing in one language. */
data class PlayTexts(
    val title: String,
    val shortDescription: String,
    val fullDescription: String,
)

/** The texts of an app's store listings on Google Play, as an edit of the app sees them. */
interface ListingTexts {
    /** Each language's texts, by Play's language codes. */
    fun texts(): Map<String, PlayTexts>

    /** Writes [texts] as the listing in [language]: a [new] language is added, an existing one keeps its video. */
    fun write(
        language: String,
        texts: PlayTexts,
        new: Boolean,
    )
}

/**
 * The texts of an app's Google Play store listing, written once for both stores: the app's App Store listing
 * ([AppStoreListing], `store/app-store`) gives them, and its Google Play folder (`store/google-play`) adds to them.
 * - From each App Store locale, in Play's language for it ([PlayLanguages.ofAppStore]): `name.txt` is the title,
 *   `promotional_text.txt` (else `subtitle.txt`) the short description and `description.txt` the full one, without the
 *   lines marked as the App Store's alone ([Store]).
 * - A folder of the Google Play one, named by Play's code (`bg`, `de-DE`...), adds a language the App Store lacks, or
 *   replaces texts of one it has: `title.txt`, `short_description.txt`, `full_description.txt`.
 * `{app.name}` stands for the app's name, and a language with no title gets the app's name.
 */
object PlayListing {
    /** A text of a listing: its file in the Google Play folder, the App Store files that give it, Play's limit. */
    private enum class Field(
        val file: String,
        val appStoreFiles: List<String>,
        val limit: Int,
    ) {
        TITLE("title.txt", listOf("name.txt"), TITLE_LIMIT),
        SHORT("short_description.txt", listOf("promotional_text.txt", "subtitle.txt"), SHORT_LIMIT),
        FULL("full_description.txt", listOf("description.txt"), FULL_LIMIT),
    }

    private const val TITLE_LIMIT = 30
    private const val SHORT_LIMIT = 80
    private const val FULL_LIMIT = 4000
    private const val NAME = "{app.name}"

    /**
     * Each language's texts, from the App Store listing in [appStore] and the Google Play folder [googlePlay] (either
     * may be missing), with [appName] for `{app.name}`; fails on a text that is missing or over Play's limits.
     */
    fun read(
        appStore: File,
        googlePlay: File,
        appName: String,
    ): Map<String, PlayTexts> {
        val fromAppStore =
            appStore
                .languages()
                .mapNotNull { dir ->
                    PlayLanguages.ofAppStore(dir.name)?.let {
                        it to
                            dir.fields(appName, appStore = true)
                    }
                }.toMap()
        val own = googlePlay.languages().associate { dir -> dir.name to dir.fields(appName, appStore = false) }
        val fields = (fromAppStore.keys + own.keys).associateWith { fromAppStore[it].orEmpty() + own[it].orEmpty() }
        val problems = fields.flatMap { (language, texts) -> problems(language, texts) }
        check(problems.isEmpty()) { "The Google Play listing can't go up:\n" + problems.joinToString("\n") }
        return fields.toSortedMap().mapValues { (_, texts) ->
            PlayTexts(
                title = texts[Field.TITLE] ?: appName,
                shortDescription = texts.getValue(Field.SHORT),
                fullDescription = texts.getValue(Field.FULL),
            )
        }
    }

    /**
     * Writes each language of [wanted] whose texts differ from the [listing]'s, and returns those languages. The
     * listing's other languages stay: every change sends the listing to Google's review again.
     */
    fun sync(
        wanted: Map<String, PlayTexts>,
        listing: ListingTexts,
    ): List<String> {
        val current = listing.texts()
        return wanted
            .filter { (language, texts) -> current[language]?.let(::normalized) != normalized(texts) }
            .onEach { (language, texts) -> listing.write(language, texts, new = language !in current) }
            .keys
            .toList()
    }

    /** [texts] as Play keeps them: line breaks of one kind, no space around. */
    private fun normalized(texts: PlayTexts): PlayTexts =
        PlayTexts(clean(texts.title), clean(texts.shortDescription), clean(texts.fullDescription))

    private fun clean(text: String): String = text.replace("\r\n", "\n").trim()

    private fun problems(
        language: String,
        texts: Map<Field, String>,
    ): List<String> =
        Field.entries.mapNotNull { field ->
            val text = texts[field]
            val length = text?.codePointCount(0, text.length) ?: 0
            when {
                text == null && field != Field.TITLE -> "$language has no ${field.file.removeSuffix(".txt")}"
                length > field.limit -> "$language: ${field.file} has $length characters, Play takes ${field.limit}"
                else -> null
            }
        }

    /** The language folders in this folder, none if it doesn't exist. */
    private fun File.languages(): List<File> = listFiles(File::isDirectory).orEmpty().sortedBy { it.name }

    /** The texts in this language folder, of the App Store's files or the Google Play folder's own. */
    private fun File.fields(
        appName: String,
        appStore: Boolean,
    ): Map<Field, String> =
        Field.entries
            .mapNotNull { field ->
                val files = if (appStore) field.appStoreFiles else listOf(field.file)
                files.firstNotNullOfOrNull { text(it, appName) }?.let { field to it }
            }.toMap()

    private fun File.text(
        name: String,
        appName: String,
    ): String? =
        resolve(name)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.replace("\r\n", "\n")
            ?.let(Store.GOOGLE_PLAY::lines)
            ?.trim()
            ?.replace(NAME, appName)
            ?.takeIf { it.isNotEmpty() }
}
