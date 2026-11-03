/** The language part of resource folder names (`values-it`, `values-zh-rCN`, `values-b+zh+Hant`). */
internal object ResourceQualifiers {
    /** Java's old codes for Hebrew, Indonesian and Yiddish, which Android resource folders still use. */
    private val MODERN = mapOf("iw" to "he", "in" to "id", "ji" to "yi")
    private val LANGUAGE = Regex("""[a-z]{2,3}""")
    private val REGION = Regex("""r([A-Z]{2})""")

    /**
     * Other folder names under which the same texts must also be found, because platforms name these languages
     * differently: older Android versions report Java's old codes (iw, in) and newer ones and iOS the current codes
     * (he, id), and iOS adds the script to Chinese (zh-Hans-CN), which only a script folder matches for every region.
     */
    val ALIASES: Map<String, List<String>> =
        mapOf(
            "iw" to listOf("he"),
            "in" to listOf("id"),
            "zh-rCN" to listOf("b+zh+Hans"),
            "zh-rTW" to listOf("b+zh+Hant"),
        )

    /** `it` → `it`, `zh-rCN` → `zh-CN`, `b+zh+Hant` → `zh-Hant`, `iw` → `he`; null for qualifiers of another kind. */
    fun languageTag(qualifiers: String): String? {
        val parts = if (qualifiers.startsWith("b+")) qualifiers.removePrefix("b+").split('+') else qualifiers.split('-')
        val language = parts.firstOrNull()?.takeIf { LANGUAGE.matches(it) } ?: return null
        val rest = parts.drop(1).map { REGION.matchEntire(it)?.groupValues?.get(1) ?: it }
        return (listOf(MODERN[language] ?: language) + rest).joinToString("-")
    }
}
