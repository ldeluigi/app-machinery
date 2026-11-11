package app.machinery.release

/**
 * The stores an app's listing texts go to. One text serves both: a line that starts with a store's [marker] is that
 * store's alone, such as `[app-store] Songs also play from Apple Music.`
 */
enum class Store(
    val marker: String,
) {
    APP_STORE("[app-store]"),
    GOOGLE_PLAY("[google-play]"),
    ;

    /** [text] as this store shows it: without the other stores' lines, and without the markers on its own. */
    fun lines(text: String): String =
        text
            .lines()
            .filterNot { line -> entries.any { it != this && line.startsWith(it.marker) } }
            .joinToString("\n") { line -> if (line.startsWith(marker)) line.removePrefix(marker).trimStart() else line }
}
