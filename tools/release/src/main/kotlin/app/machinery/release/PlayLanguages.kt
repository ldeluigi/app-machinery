package app.machinery.release

import java.io.File

/** The languages of Google Play's store listings, and the ones the app speaks. */
object PlayLanguages {
    /** The language of the app's default store listing and of its notes, the one Play falls back on. */
    const val DEFAULT = "en-US"

    /** Every language a Google Play store listing can have, by Play's own codes (Play Console Help, 9844778). */
    val CODES =
        """
            af am ar az-AZ be bg bn-BD ca cs-CZ da-DK de-DE el-GR en-AU en-CA en-GB en-IN en-SG en-US en-ZA es-419
            es-ES es-US et eu-ES fa fa-AE fa-AF fa-IR fi-FI fil fr-CA fr-FR gl-ES gu hi-IN hr hu-HU hy-AM id is-IS
            it-IT iw-IL ja-JP ka-GE kk km-KH kn-IN ko-KR ky-KG lo-LA lt lv mk-MK ml-IN mn-MN mr-IN ms ms-MY my-MM
            ne-NP nl-NL no-NO pa pl-PL pt-BR pt-PT rm ro ru-RU si-LK sk sl sq sr sv-SE sw ta-IN te-IN th tr-TR uk ur
            vi zh-CN zh-HK zh-TW zu
        """.trim().split(Regex("""\s+"""))

    /** Where Play keeps a language's old code, or another code for it. */
    private val PLAY_LANGUAGE = mapOf("he" to "iw", "nb" to "no", "nn" to "no", "tl" to "fil")

    /** Android's old codes in resource qualifiers, by today's. */
    private val ANDROID_OLD_CODES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    private val REGION = Regex("""[A-Z]{2}|\d{3}""")

    /** `de` or `zh-rTW`: a language, and maybe its region. */
    private val LEGACY_QUALIFIER = Regex("""([a-z]{2,3})(?:-r([A-Z]{2}|\d{3}))?""")

    /** `b+sr+Latn`: a BCP 47 tag, with + for -. */
    private val BCP_47_QUALIFIER = Regex("""b\+[a-z]{2,3}(?:\+[A-Za-z0-9]{2,8})*""")

    /**
     * The app's translations in [res] (its `values-<qualifier>` directories with a `strings.xml`) as BCP 47 tags,
     * such as `de`, `he` or `zh-TW`, with Play's store listing languages each one covers.
     */
    fun ofApp(res: File): Map<String, List<String>> =
        res
            .listFiles { dir -> dir.name.startsWith("values-") && dir.resolve("strings.xml").isFile }
            .orEmpty()
            .mapNotNull { tag(it.name.removePrefix("values-")) }
            .sorted()
            .associateWith(::codes)
            .filterValues { it.isNotEmpty() }

    /**
     * Play's store listing languages that [tag] covers: the one of its region, if it has one, or else every
     * listing of its language, such as both `pt-BR` and `pt-PT` for `pt`. None when Play doesn't have it.
     */
    fun codes(tag: String): List<String> {
        val parts = tag.split('-')
        val language = parts.first().let { PLAY_LANGUAGE[it] ?: it }
        val region = parts.drop(1).firstOrNull(REGION::matches)
        val all = CODES.filter { it == language || it.startsWith("$language-") }
        return if (region == null) all else all.filter { it == "$language-$region" }
    }

    /**
     * The BCP 47 tag of a resource qualifier that is only a language (`de`, `zh-rTW`, `b+sr+Latn`), or null for any
     * other qualifier (`night`, `de-land`).
     */
    fun tag(qualifier: String): String? {
        val legacy = LEGACY_QUALIFIER.matchEntire(qualifier)
        val parts =
            when {
                legacy != null -> legacy.groupValues.drop(1).filter(String::isNotEmpty)
                BCP_47_QUALIFIER.matches(qualifier) -> qualifier.split('+').drop(1)
                else -> return null
            }
        val language = parts.first()
        return (listOf(ANDROID_OLD_CODES[language] ?: language) + parts.drop(1)).joinToString("-")
    }
}
