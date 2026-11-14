package app.machinery.release

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** An achievement's name and what it takes, in one language. */
data class AchievementText(
    val name: String,
    val description: String,
)

/**
 * An achievement of a game, as the game services keep it: its [id] (Game Center's achievement id, and the name of the
 * string that holds Play Games' id), its [points], whether it is [secret] (hidden until unlocked), and its [texts] by
 * the app's languages (BCP 47 tags).
 */
data class Achievement(
    val id: String,
    val points: Int,
    val secret: Boolean,
    val texts: Map<String, AchievementText>,
)

/**
 * A game's achievements, as its repository lists them ([FILE]): one per line, in the order the services list them,
 * its id, its points and, for one hidden until unlocked, `secret`. Lines with `=` are settings: where the texts are
 * ([NAME], [DESCRIPTION]: string keys with `{id}` for the achievement's id, in the app's Compose resources [STRINGS]),
 * where the pictures are ([PICTURES]) and where Play Games' ids go ([PLAY_GAMES_IDS]). Paths are from the app's
 * folder. `#` starts a comment.
 */
data class Achievements(
    val list: List<Achievement>,
    /** The language of the app's default texts, its `values` folder. */
    val defaultLanguage: String,
    private val settings: Map<String, String>,
) {
    /** The setting [key], or its default. */
    fun setting(key: String): String = settings[key]?.takeIf { it.isNotBlank() } ?: DEFAULTS.getValue(key)

    /**
     * Each achievement's texts in a store's languages: [codes] gives the store's languages an app language covers,
     * and each store language takes the app language that covers it most closely (`pt-PT` before `pt`).
     */
    fun localized(codes: (String) -> List<String>): List<Pair<Achievement, Map<String, AchievementText>>> {
        val languages = list.flatMap { it.texts.keys }.toSet()
        val source =
            languages
                .flatMap { tag -> codes(tag).map { code -> code to tag } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, tags) -> tags.maxBy { tag -> tag.count { it == '-' } } }
                .toSortedMap()
        return list.map { achievement ->
            achievement to source.mapNotNull { (code, tag) -> achievement.texts[tag]?.let { code to it } }.toMap()
        }
    }

    companion object {
        /** Where an app lists its achievements. */
        const val FILE = "store/achievements.txt"

        /** The string key of an achievement's name, with `{id}`. */
        const val NAME = "name"

        /** The string key of what an achievement takes, with `{id}`. */
        const val DESCRIPTION = "description"

        /** The app's Compose resources, whose `values*` folders hold the texts. */
        const val STRINGS = "strings"

        /** The folder of Game Center's pictures, `<id>.png` each, 512 or 1024 pixels square. */
        const val PICTURES = "pictures"

        /** The Android resource file the app reads Play Games' ids from ([PlayGamesIds]). */
        const val PLAY_GAMES_IDS = "play-games-ids"

        /** Both services together take at most this many points for a game. */
        const val MAX_TOTAL = 1_000

        /** One achievement takes at most this many points in Game Center. */
        const val MAX_POINTS = 100

        /** Google Play Games takes points in steps of this. */
        const val POINT_STEP = 5

        private const val SECRET = "secret"
        private val ID = Regex("""[a-z][a-z0-9_]{0,99}""")
        private val DEFAULTS =
            mapOf(
                STRINGS to "composeApp/src/commonMain/composeResources",
                PICTURES to "composeApp/build/store-art/achievements/game-center",
                PLAY_GAMES_IDS to "composeApp/src/androidMain/res/values/games-ids.xml",
            )

        /**
         * The achievements [file] lists, with their texts from the app's string resources, the default ones in
         * [defaultLanguage]; [root] is the app's folder. Fails with every problem at once.
         */
        fun read(
            file: File,
            defaultLanguage: String,
            root: File = File("."),
        ): Achievements {
            val lines =
                file
                    .readLines(Charsets.UTF_8)
                    .map { it.substringBefore('#').trim() }
                    .filter(String::isNotEmpty)
            val settings =
                lines
                    .filter { '=' in it }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val strings =
                StringResources
                    .read(root.resolve(settings[STRINGS] ?: DEFAULTS.getValue(STRINGS)))
                    .mapKeys { it.key.ifEmpty { defaultLanguage } }
            val problems = mutableListOf<String>()
            val keys = listOf(NAME, DESCRIPTION).associateWith { settings[it] }
            keys.filterValues { it == null || "{id}" !in it }.keys.forEach { key ->
                problems += "Set $key = <a string key with {id}> in ${file.path}"
            }
            val list =
                lines.filterNot { '=' in it }.mapNotNull { line ->
                    val parts = line.split(Regex("""\s+"""))
                    val id = parts.first()
                    val points = parts.getOrNull(1)?.toIntOrNull()
                    val secret = parts.getOrNull(2) == SECRET
                    if (points == null || parts.size > if (secret) 3 else 2) {
                        problems += "\"$line\": write the id, the points and maybe \"$SECRET\""
                        return@mapNotNull null
                    }
                    val texts =
                        strings
                            .mapNotNull { (tag, values) ->
                                val name = keys[NAME]?.let { values[it.replace("{id}", id)] }
                                val description = keys[DESCRIPTION]?.let { values[it.replace("{id}", id)] }
                                if (name != null && description != null) {
                                    tag to AchievementText(name, description)
                                } else {
                                    null
                                }
                            }.toMap()
                    Achievement(id, points, secret, texts)
                }
            problems += problems(list, defaultLanguage)
            check(problems.isEmpty()) {
                "The achievements in ${file.path} can't go up:\n" + problems.joinToString("\n")
            }
            return Achievements(list, defaultLanguage, settings)
        }

        private fun problems(
            list: List<Achievement>,
            defaultLanguage: String,
        ): List<String> {
            val total = list.sumOf(Achievement::points)
            val twice = list.groupBy(Achievement::id).filterValues { it.size > 1 }.keys
            return list.flatMap { achievement ->
                val id = achievement.id
                listOfNotNull(
                    "\"$id\" isn't an id: lower case letters, digits and _".takeUnless { ID.matches(id) },
                    "$id has ${achievement.points} points: 0 to $MAX_POINTS, in steps of $POINT_STEP".takeUnless {
                        achievement.points in 0..MAX_POINTS && achievement.points % POINT_STEP == 0
                    },
                    "$id has no name and description in $defaultLanguage (the values folder)".takeUnless {
                        achievement.texts[defaultLanguage]?.let { text ->
                            text.name.isNotBlank() && text.description.isNotBlank()
                        } ?: false
                    },
                )
            } +
                listOfNotNull(
                    "The achievements have $total points: the services take $MAX_TOTAL at most"
                        .takeIf { total > MAX_TOTAL },
                    "Ids listed twice: ${twice.joinToString()}".takeIf { twice.isNotEmpty() },
                    "No achievements listed".takeIf { list.isEmpty() },
                )
        }
    }
}

/** An app's texts, from its Compose (or Android) string resources. */
object StringResources {
    /**
     * Every `strings.xml` of [dir]'s `values*` folders, by the language each is in: the default folder's under the
     * empty key `""` until [Achievements] names it, the others' by their BCP 47 tag ([PlayLanguages.tag]).
     */
    fun read(dir: File): Map<String, Map<String, String>> =
        dir
            .listFiles { folder -> folder.name.startsWith("values") && folder.resolve("strings.xml").isFile }
            .orEmpty()
            .mapNotNull { folder ->
                val tag = if (folder.name == "values") "" else PlayLanguages.tag(folder.name.removePrefix("values-"))
                tag?.let { it to strings(folder.resolve("strings.xml")) }
            }.toMap()

    /** The strings of one `strings.xml`, by name, as the app shows them. */
    fun strings(file: File): Map<String, String> {
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

    /** A resource string's text without Android's escapes and enclosing quotes. */
    fun unescape(text: String): String {
        val quoted = text.length > 1 && text.startsWith('"') && text.endsWith('"')
        val body = if (quoted) text.substring(1, text.length - 1) else text
        return buildString {
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    append(
                        when (val next = body[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> next
                        },
                    )
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

/** The App Store's languages, as App Store Connect names its locales, Game Center's included. */
object AppStoreLocales {
    /** Every App Store locale. */
    val CODES =
        """
            ar-SA ca cs da de-DE el en-AU en-CA en-GB en-US es-ES es-MX fi fr-CA fr-FR he hi hr hu id it ja ko ms nl-NL
            no pl pt-BR pt-PT ro ru sk sv th tr uk vi zh-Hans zh-Hant
        """.trim().split(Regex("""\s+"""))

    private val LANGUAGE = mapOf("nb" to "no", "nn" to "no", "iw" to "he", "in" to "id")
    private val SCRIPT =
        mapOf("TW" to "zh-Hant", "HK" to "zh-Hant", "MO" to "zh-Hant", "CN" to "zh-Hans", "SG" to "zh-Hans")

    /**
     * The App Store locales [tag] covers: the one of its region, if it has one, or else every locale of its language,
     * such as both `pt-BR` and `pt-PT` for `pt`. None when the App Store doesn't have it.
     */
    fun codes(tag: String): List<String> {
        val parts = tag.split('-')
        val language = parts.first().let { LANGUAGE[it] ?: it }
        val rest = parts.drop(1)
        if (language == "zh") {
            val script = rest.firstOrNull { it == "Hans" || it == "Hant" }?.let { "zh-$it" }
            val chinese = script ?: rest.firstNotNullOfOrNull { SCRIPT[it] }
            return listOfNotNull(chinese).ifEmpty { listOf("zh-Hans", "zh-Hant") }
        }
        val all = CODES.filter { it == language || it.startsWith("$language-") }
        val region = rest.firstOrNull { it.length == 2 && it.all(Char::isUpperCase) }
        return if (region == null) all else all.filter { it == "$language-$region" || it == language }
    }
}
