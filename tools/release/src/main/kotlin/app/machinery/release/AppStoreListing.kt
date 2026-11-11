package app.machinery.release

import java.io.File
import java.util.Properties

private const val SHORT_TEXT = 30
private const val LONG_TEXT = 4000
private const val KEYWORDS_TEXT = 100
private const val PROMOTIONAL_TEXT_LIMIT = 170

/** A text of the App Store listing in one language: its file in the language's folder and Apple's limit. */
enum class ListingText(
    val file: String,
    val limit: Int,
    val required: Boolean = false,
) {
    NAME("name.txt", SHORT_TEXT, required = true),
    SUBTITLE("subtitle.txt", SHORT_TEXT),
    DESCRIPTION("description.txt", LONG_TEXT, required = true),
    KEYWORDS("keywords.txt", KEYWORDS_TEXT),
    PROMOTIONAL_TEXT("promotional_text.txt", PROMOTIONAL_TEXT_LIMIT),
    BETA_DESCRIPTION("beta_description.txt", LONG_TEXT),
}

/**
 * An app's App Store listing as its repository keeps it, in a folder (`store/app-store`):
 * - `app-store.properties`: what isn't written in a language: the categories, the URLs, the price and the countries,
 *   the age rating's answers (`ageRating.<attribute>`, as the API names them), the release type, TestFlight's group;
 * - `review-notes.txt`: the notes for App Review;
 * - one folder per App Store locale (`en-US`, `de-DE`, `ja`...) with the [ListingText] files it has.
 *
 * `{app.name}` in any of them stands for the app's name, read from its gradle.properties ([AppIdentity]). The texts
 * serve Google Play too ([PlayListing]): a line that starts with `[google-play]` is left out here, and `[app-store]`
 * is taken off the lines that are the App Store's alone ([Store]).
 */
data class AppStoreListing(
    val settings: Map<String, String>,
    val texts: Map<String, Map<ListingText, String>>,
    val reviewNotes: String?,
) {
    /** The setting [key], or null when it is missing or empty. */
    fun setting(key: String): String? = settings[key]?.takeIf { it.isNotBlank() }

    /** The comma-separated setting [key], as a list. */
    fun list(key: String): List<String> =
        setting(key)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** The settings whose keys start with [prefix], by the rest of their keys. */
    fun group(prefix: String): Map<String, String> =
        settings.filterKeys { it.startsWith(prefix) }.mapKeys { (key, _) -> key.removePrefix(prefix) }

    companion object {
        /** The settings file of the listing's folder. */
        const val SETTINGS = "app-store.properties"

        /** The notes for App Review in the listing's folder. */
        const val REVIEW_NOTES = "review-notes.txt"

        private const val REVIEW_NOTES_LIMIT = 4000
        private const val NAME = "{app.name}"

        /** The listing in [dir], with [appName] for `{app.name}`; fails on any text over Apple's limits. */
        fun read(
            dir: File,
            appName: String,
        ): AppStoreListing {
            check(dir.isDirectory) { "No App Store listing in ${dir.path}" }
            val settingsFile = dir.resolve(SETTINGS)
            val settings =
                Properties()
                    .apply { if (settingsFile.isFile) settingsFile.reader(Charsets.UTF_8).use(::load) }
                    .let { properties ->
                        properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
                    }
            val texts =
                dir
                    .listFiles(File::isDirectory)
                    .orEmpty()
                    .sortedBy { it.name }
                    .associate { locale ->
                        locale.name to
                            ListingText.entries
                                .mapNotNull { text -> locale.text(text.file, appName)?.let { text to it } }
                                .toMap()
                    }.filterValues { it.isNotEmpty() }
            val notes = dir.text(REVIEW_NOTES, appName)
            val problems = problems(texts) + listOfNotNull(notes?.let { tooLong(REVIEW_NOTES, it, REVIEW_NOTES_LIMIT) })
            check(problems.isEmpty()) {
                "The App Store listing in ${dir.path} can't go up:\n" +
                    problems.joinToString("\n")
            }
            return AppStoreListing(settings, texts, notes)
        }

        private fun problems(texts: Map<String, Map<ListingText, String>>): List<String> =
            texts.flatMap { (locale, fields) ->
                ListingText.entries.mapNotNull { text ->
                    val value = fields[text]
                    when {
                        value == null && text.required -> "$locale/${text.file} is missing"
                        value != null -> tooLong("$locale/${text.file}", value, text.limit)
                        else -> null
                    }
                }
            }

        private fun tooLong(
            file: String,
            value: String,
            limit: Int,
        ): String? {
            val length = value.codePointCount(0, value.length)
            return if (length > limit) "$file has $length characters, Apple takes $limit" else null
        }

        private fun File.text(
            name: String,
            appName: String,
        ): String? =
            resolve(name)
                .takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.replace("\r\n", "\n")
                ?.let(Store.APP_STORE::lines)
                ?.trim()
                ?.replace(NAME, appName)
                ?.takeIf { it.isNotEmpty() }
    }
}
