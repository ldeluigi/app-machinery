package app.machinery.release

/**
 * How big a release is. [updatePriority] is how insistently the app asks users to update (Play in-app updates): Play
 * gives an app the highest priority of the versions between the installed one and the newest, so a fix still counts
 * when maintenance releases followed it.
 */
enum class ReleaseType(
    val updatePriority: Int,
) {
    MAJOR(5),
    MINOR(3),
    PATCH(2),

    /** A patch release that only does maintenance (dependencies, the build, the app's own maintenance scopes). */
    MAINTENANCE(1),
    ;

    companion object {
        /**
         * The type of the release that made [version]: semantic versioning resets what follows the bumped part. A patch
         * release whose [changelog] lists maintenance [scopes] only is [MAINTENANCE].
         */
        fun of(
            version: String,
            changelog: String = "",
            scopes: Map<String, String> = PlayNotes.MAINTENANCE_SCOPES,
        ): ReleaseType {
            val (_, minor, patch) = Versions.parts(version)
            return when {
                patch > 0 && PlayNotes.maintenanceOnly(changelog, scopes) -> MAINTENANCE
                patch > 0 -> PATCH
                minor > 0 -> MINOR
                else -> MAJOR
            }
        }
    }
}

/** Semantic versions such as `1.10.0`, without the tag's `v`. */
object Versions {
    /** Newest first: 1.10.0 before 1.9.2. */
    val NEWEST_FIRST: Comparator<String> =
        compareByDescending<String> { parts(it)[0] }
            .thenByDescending { parts(it)[1] }
            .thenByDescending { parts(it)[2] }

    /** The major, minor and patch numbers of [version]. */
    fun parts(version: String): List<Int> =
        (version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
}

/** One release in Google Play's "What's new": its [version] and its [changes], a plain sentence each. */
data class ReleaseChanges(
    val version: String,
    val changes: List<String>,
)

/** Google Play's "What's new", made from the Markdown changelogs semantic-release writes. */
object PlayNotes {
    /** Google Play's limit for release notes per language. */
    const val LIMIT = 500

    /** The change of the first release, which has no history worth listing. */
    const val FIRST_RELEASE = "First release."

    /** The change of a release whose changelog lists none. */
    const val NO_CHANGES = "Bug fixes and improvements."

    /** What ends notes that leave out some of their last release's changes. */
    private const val MORE = "\n…"

    private val BULLET = Regex("""^[*-] """)
    private val SCOPE = Regex("""^[*-] \*\*([^*:]+):\*\* """)
    private val SCOPE_LABEL = Regex("""^\*\*[^*:]+:\*\* """)
    private val COMMIT_LINK = Regex("""\s*\(\[[0-9a-f]{7,}]\([^)]*\)\)""")
    private val LINK = Regex("""\[([^]]+)]\([^)]*\)""")
    private val PULL_REQUEST = Regex("""\s*\(#\d+\)""")

    /** What the changelog appends to a commit that names issues in its message: ", closes #12 #13", once unlinked. */
    private val CLOSED_ISSUES = Regex(""",\s*closes(\s+\S*#\d+)+""")

    /**
     * One friendly line for each maintenance scope, whatever its commits say. An app adds its own (the release
     * workflow's `scope-notes`, `scope: line` each).
     */
    val MAINTENANCE_SCOPES =
        mapOf(
            "deps" to "Updated components for stability and security.",
            "machinery" to "Kept the app up to date with the latest Android versions.",
            "build" to "Kept the app up to date with the latest Android versions.",
        )

    /** The scope lines written `scope: line`, one a line, as the release workflow passes them. */
    fun scopes(written: String): Map<String, String> =
        written
            .lineSequence()
            .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
            .associate { (scope, text) -> scope.trim() to text.trim() }
            .filter { (scope, text) -> scope.isNotEmpty() && text.isNotEmpty() }

    /**
     * The changes [changelog] lists, as plain sentences without scopes, links, commit hashes, or issue and pull
     * request numbers. Maintenance [scopes] get one friendly line each.
     */
    fun changes(
        changelog: String,
        scopes: Map<String, String> = MAINTENANCE_SCOPES,
    ): List<String> =
        bullets(changelog)
            .map { line ->
                scopes[scope(line)]
                    ?: line
                        .replace(BULLET, "")
                        .replace(SCOPE_LABEL, "")
                        .replace(COMMIT_LINK, "")
                        .replace(LINK, "$1")
                        .replace(CLOSED_ISSUES, "")
                        .replace(PULL_REQUEST, "")
                        .trim()
                        .replaceFirstChar(Char::uppercaseChar)
            }.distinct()
            .toList()
            .ifEmpty { listOf(NO_CHANGES) }

    /**
     * Whether [changelog] lists changes and all of them have maintenance [scopes]: nothing users would notice, so the
     * app doesn't ask them to update.
     */
    fun maintenanceOnly(
        changelog: String,
        scopes: Map<String, String> = MAINTENANCE_SCOPES,
    ): Boolean {
        val lines = bullets(changelog).toList()
        return lines.isNotEmpty() && lines.all { scope(it) in scopes }
    }

    /** The lines of [changelog] that list a change. */
    private fun bullets(changelog: String): Sequence<String> =
        changelog.lineSequence().map(String::trim).filter(BULLET::containsMatchIn)

    /** The scope of a changelog [line], `deps` in `* **deps:** bump ...`, if it has one. */
    private fun scope(line: String): String? = SCOPE.find(line)?.groupValues?.get(1)

    /**
     * Google Play's notes for [releases], newest first: each release's version as the app shows it, then its changes
     * as bullet points, as many as fit in [LIMIT] characters, so that someone a few versions behind sees what changed
     * since theirs. When the last release shown doesn't fit whole, the notes end with "…".
     */
    fun compose(releases: List<ReleaseChanges>): String {
        val entries = entries(releases)
        val shown = fitting(entries)
        val cut = shown < entries.size && entries[shown].first == entries[shown - 1].first
        val notes = render(entries.take(shown)) + if (cut) MORE else ""
        return if (notes.length <= LIMIT) notes else notes.take(LIMIT - 1) + "…"
    }

    /** The releases [compose] shows, at least in part, with all their changes. */
    fun shown(releases: List<ReleaseChanges>): List<ReleaseChanges> {
        val entries = entries(releases)
        return releases.take(
            entries
                .take(fitting(entries))
                .map { it.first }
                .distinct()
                .size,
        )
    }

    /** Each change of [releases] with its release's version, in order. */
    private fun entries(releases: List<ReleaseChanges>): List<Pair<String, String>> =
        releases.flatMap { release -> release.changes.map { release.version to it } }

    /** How many of [entries] fit, with room for "…" when they don't all: at least one. */
    private fun fitting(entries: List<Pair<String, String>>): Int =
        if (render(entries).length <= LIMIT) {
            entries.size
        } else {
            (entries.size - 1 downTo 1).firstOrNull { render(entries.take(it)).length + MORE.length <= LIMIT } ?: 1
        }

    private fun render(entries: List<Pair<String, String>>): String =
        entries
            .groupBy({ it.first }, { it.second })
            .entries
            .joinToString("\n") { (version, changes) ->
                (listOf("v$version") + changes.map { "• $it" }).joinToString("\n")
            }
}
