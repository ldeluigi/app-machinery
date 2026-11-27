import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the texts a release sends to the stores against what the stores take, as the release tool reads them
 * (app-machinery's `tools/release`): there, a text a store refuses stops the release's listing jobs once the release is
 * out; here, it fails the pull request that brings it.
 *
 * - The App Store listing (`store/app-store`): each language's texts within Apple's limits, its name and description
 *   there, and the notes for App Review.
 * - The Google Play listing, from the App Store's texts and the Google Play folder's own (`store/google-play`): each
 *   language's title, short and full description, within Play's limits.
 * - A game's achievements (`store/achievements.txt`), with their texts in the Compose resources: every language's
 *   names within Google Play Games' limit and descriptions within Game Center's.
 *
 * Lengths are in characters (Unicode code points), as the stores count them; an app without one of these has nothing
 * of it to check.
 */
@CacheableTask
abstract class CheckStoreTexts : DefaultTask() {
    /** The app's `store` folder. */
    @get:Internal
    abstract val store: DirectoryProperty

    /** The files of [store], so that the check runs again when one changes. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val storeFiles: ConfigurableFileCollection

    /** The Compose resources, whose `values*` folders hold the achievements' texts. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    /** The app's name, for `{app.name}` in the listings. */
    @get:Input
    abstract val appName: Property<String>

    /** Where the findings are written, one per line (empty when all is well). */
    @get:OutputFile
    abstract val report: RegularFileProperty

    /** Checks every text and fails, listing each one a store would refuse, if any. */
    @TaskAction
    fun check() {
        val root = store.get().asFile
        val appStore = root.resolve("app-store")
        val problems =
            appStoreProblems(appStore) +
                playProblems(appStore, root.resolve("google-play")) +
                achievementProblems(root.resolve("achievements.txt"))
        report.get().asFile.writeText(problems.joinToString("\n", postfix = if (problems.isEmpty()) "" else "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${problems.size} store text(s) a store would refuse:\n" + problems.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun appStoreProblems(dir: File): List<String> {
        val texts =
            dir.languages().flatMap { locale ->
                APP_STORE_TEXTS.mapNotNull { (file, limit) ->
                    val text = locale.text(file, APP_STORE)
                    when {
                        text == null && file in APP_STORE_REQUIRED -> "app-store/${locale.name}/$file is missing"
                        text != null -> tooLong("app-store/${locale.name}/$file", text, limit, "Apple")
                        else -> null
                    }
                }
            }
        val notes = dir.text(REVIEW_NOTES, APP_STORE)?.let { tooLong("app-store/$REVIEW_NOTES", it, LONG, "Apple") }
        return texts + listOfNotNull(notes)
    }

    private fun playProblems(
        appStore: File,
        googlePlay: File,
    ): List<String> {
        val fromAppStore =
            appStore
                .languages()
                .mapNotNull { locale ->
                    playLanguage(locale.name)?.let { language ->
                        language to PLAY_TEXTS.mapNotNull { field -> field.read(locale, appStore = true) }.toMap()
                    }
                }.toMap()
        val own =
            googlePlay.languages().associate { dir ->
                dir.name to PLAY_TEXTS.mapNotNull { field -> field.read(dir, appStore = false) }.toMap()
            }
        return (fromAppStore.keys + own.keys).sorted().flatMap { language ->
            val texts = fromAppStore[language].orEmpty() + own[language].orEmpty()
            PLAY_TEXTS.mapNotNull { field ->
                val text = texts[field]
                when {
                    text == null && field.required -> "Google Play's $language has no ${field.file}"
                    text != null -> tooLong("Google Play's $language ${field.file}", text, field.limit, "Play")
                    else -> null
                }
            }
        }
    }

    private fun achievementProblems(file: File): List<String> {
        val keys = if (file.isFile) achievementKeys(file) else emptyList()
        if (keys.isEmpty()) return emptyList()
        return resources
            .get()
            .asFile
            .listFiles { dir -> dir.name.startsWith("values") && dir.resolve("strings.xml").isFile }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { dir ->
                val strings = strings(dir.resolve("strings.xml"))
                keys.mapNotNull { (key, limit, service) ->
                    strings[key]?.let { tooLong("${dir.name} $key", it, limit, service) }
                }
            }
    }

    /** The string keys of the achievements [file] lists, each with the limit of the service that sets the lowest. */
    private fun achievementKeys(file: File): List<Triple<String, Int, String>> {
        val lines = file.readLines().map { it.substringBefore('#').trim() }.filter(String::isNotEmpty)
        val settings =
            lines
                .filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        return lines.filterNot { '=' in it }.map { it.split(Regex("""\s+""")).first() }.flatMap { id ->
            listOfNotNull(
                settings["name"]?.let { Triple(it.replace("{id}", id), ACHIEVEMENT_NAME, "Google Play Games") },
                settings["description"]?.let {
                    Triple(it.replace("{id}", id), ACHIEVEMENT_DESCRIPTION, "Game Center")
                },
            )
        }
    }

    /** A text of the Google Play listing: its file in the Google Play folder, the App Store files that give it. */
    private enum class PlayText(
        val file: String,
        val appStoreFiles: List<String>,
        val limit: Int,
        val required: Boolean,
    ) {
        TITLE("title.txt", listOf("name.txt"), SHORT, required = false),
        SHORT_DESCRIPTION(
            "short_description.txt",
            listOf("promotional_text.txt", "subtitle.txt"),
            MEDIUM,
            required = true,
        ),
        FULL_DESCRIPTION("full_description.txt", listOf("description.txt"), LONG, required = true),
    }

    /** This text in the language folder [dir], of the App Store's files or the Google Play folder's own. */
    private fun PlayText.read(
        dir: File,
        appStore: Boolean,
    ): Pair<PlayText, String>? =
        (if (appStore) appStoreFiles else listOf(file))
            .firstNotNullOfOrNull { dir.text(it, GOOGLE_PLAY) }
            ?.let { this to it }

    /** The language folders in this folder, none if it doesn't exist. */
    private fun File.languages(): List<File> = listFiles(File::isDirectory).orEmpty().sortedBy { it.name }

    /** The text of [name] in this folder as the store of [marker] shows it, or null when it is missing or empty. */
    private fun File.text(
        name: String,
        marker: String,
    ): String? =
        resolve(name)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.replace("\r\n", "\n")
            ?.lines()
            ?.filterNot { line -> MARKERS.any { it != marker && line.startsWith(it) } }
            ?.joinToString("\n") { line ->
                if (line.startsWith(marker)) line.removePrefix(marker).trimStart() else line
            }?.trim()
            ?.replace("{app.name}", appName.get())
            ?.takeIf { it.isNotEmpty() }

    private fun tooLong(
        what: String,
        text: String,
        limit: Int,
        store: String,
    ): String? {
        val length = text.codePointCount(0, text.length)
        return "$what has $length characters, $store takes $limit".takeIf { length > limit }
    }

    /** The strings of a `strings.xml`, by name, as the app shows them: without Android's escapes and quotes. */
    private fun strings(file: File): Map<String, String> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    private fun unescape(text: String): String {
        val quoted = text.length > 1 && text.startsWith('"') && text.endsWith('"')
        val body = if (quoted) text.substring(1, text.length - 1) else text
        return ESCAPE.replace(body) { match ->
            when (val escaped = match.groupValues[1]) {
                "n" -> "\n"
                "t" -> "\t"
                else -> escaped
            }
        }
    }

    /**
     * Play's store listing language for an App Store locale (`de-DE`, `ja`, `zh-Hans`...), or null when Play has none:
     * the same code when Play has it, else the language without a region, else the language's only listing.
     */
    private fun playLanguage(locale: String): String? {
        APP_STORE_LOCALES[locale]?.let { return it }
        val language = locale.substringBefore('-').let { PLAY_LANGUAGE[it] ?: it }
        val listings = PLAY_CODES.filter { it == language || it.startsWith("$language-") }
        return when {
            locale in PLAY_CODES -> locale
            language in listings -> language
            else -> listings.singleOrNull()
        }
    }

    private companion object {
        const val SHORT = 30
        const val MEDIUM = 80
        const val LONG = 4000
        const val REVIEW_NOTES = "review-notes.txt"
        const val APP_STORE = "[app-store]"
        const val GOOGLE_PLAY = "[google-play]"
        val MARKERS = listOf(APP_STORE, GOOGLE_PLAY)

        /** Google Play Games takes achievement names of at most this many characters. */
        const val ACHIEVEMENT_NAME = 100

        /** Game Center takes achievement descriptions of at most this many characters (Google Play Games, 500). */
        const val ACHIEVEMENT_DESCRIPTION = 120

        /** The App Store listing's files in a language's folder, with Apple's limits. */
        val APP_STORE_TEXTS =
            mapOf(
                "name.txt" to SHORT,
                "subtitle.txt" to SHORT,
                "description.txt" to LONG,
                "keywords.txt" to 100,
                "promotional_text.txt" to 170,
                "beta_description.txt" to LONG,
                "what_to_test.txt" to LONG,
            )
        val APP_STORE_REQUIRED = setOf("name.txt", "description.txt")
        val PLAY_TEXTS = PlayText.entries
        val ESCAPE = Regex("""\\(.)""")

        /** Every language a Google Play store listing can have, by Play's own codes (Play Console Help, 9844778). */
        val PLAY_CODES =
            """
            af am ar az-AZ be bg bn-BD ca cs-CZ da-DK de-DE el-GR en-AU en-CA en-GB en-IN en-SG en-US en-ZA es-419
            es-ES es-US et eu-ES fa fa-AE fa-AF fa-IR fi-FI fil fr-CA fr-FR gl-ES gu hi-IN hr hu-HU hy-AM id is-IS
            it-IT iw-IL ja-JP ka-GE kk km-KH kn-IN ko-KR ky-KG lo-LA lt lv mk-MK ml-IN mn-MN mr-IN ms ms-MY my-MM
            ne-NP nl-NL no-NO pa pl-PL pt-BR pt-PT rm ro ru-RU si-LK sk sl sq sr sv-SE sw ta-IN te-IN th tr-TR uk ur
            vi zh-CN zh-HK zh-TW zu
            """.trim().split(Regex("""\s+"""))

        /** Where Play keeps a language's old code, or another code for it. */
        val PLAY_LANGUAGE = mapOf("he" to "iw", "nb" to "no", "nn" to "no", "tl" to "fil")

        /** App Store locales that name a script or a wider region than Play's listing does. */
        val APP_STORE_LOCALES = mapOf("es-MX" to "es-419", "zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW")
    }
}
