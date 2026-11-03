package app.machinery.release

import java.io.File

/**
 * Writes Google Play's notes for the release semantic-release just made to `whatsnew/whatsnew-<language>`, in the
 * app's folder (the working directory): the changes of this release and of the ones before it, as many as fit, in
 * English (`en-US`) and in each of the app's languages that Claude Code translates them into. Without Claude Code, or
 * when it fails, they stay in English only.
 *
 * From the environment: `RELEASE_NOTES` (its Markdown changelog), `RELEASE_VERSION` and `LAST_RELEASE_VERSION` (empty
 * for the first release); `GITHUB_REPOSITORY`, `GITHUB_API_URL` and `GH_TOKEN` to read the earlier releases (only
 * this one's changes without them); `APP_NAME` and `APP_ABOUT` (what the app is, for the translations), `TRANSLATIONS`
 * (the app's Compose resources, whose `values-*` folders give its languages) and `SCOPE_NOTES` (the app's own
 * maintenance scopes, `scope: line` each). Writes `type` and `priority` to `$GITHUB_OUTPUT` in GitHub Actions.
 */
fun main() {
    val name = env("APP_NAME") ?: "The app"
    val scopes = PlayNotes.MAINTENANCE_SCOPES + PlayNotes.scopes(env("SCOPE_NOTES").orEmpty())
    val version =
        checkNotNull(System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }) { "RELEASE_VERSION is not set" }
    val first = System.getenv("LAST_RELEASE_VERSION").isNullOrBlank()
    val latest =
        ReleaseChanges(
            version,
            if (first) listOf(PlayNotes.FIRST_RELEASE) else PlayNotes.changes(env("RELEASE_NOTES").orEmpty(), scopes),
        )
    val releases = listOf(latest) + if (first) emptyList() else earlierThan(version, scopes)
    val about = listOfNotNull(name, env("APP_ABOUT")).joinToString(", ")
    val english = mapOf(PlayLanguages.DEFAULT to PlayNotes.compose(releases))
    val notes = english + translated(PlayNotes.shown(releases), about)
    val type = ReleaseType.of(version)
    File("whatsnew").mkdirs()
    notes.forEach { (language, text) -> File("whatsnew/whatsnew-$language").writeText(text) }
    println(
        "$name $version (${type.name.lowercase()} release, in-app update priority ${type.updatePriority}), " +
            "notes in ${notes.keys.sorted().joinToString(" ")}:\n${notes.getValue(PlayLanguages.DEFAULT)}",
    )
    System.getenv("GITHUB_OUTPUT")?.let { output ->
        File(output).appendText("type=${type.name.lowercase()}\npriority=${type.updatePriority}\n")
    }
}

/** The releases on GitHub before [version], newest first; none when they can't be read. */
private fun earlierThan(
    version: String,
    scopes: Map<String, String>,
): List<ReleaseChanges> {
    val repository = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: return emptyList()
    val api = System.getenv("GITHUB_API_URL")?.takeIf { it.isNotBlank() } ?: "https://api.github.com"
    return runCatching { GitHubReleases(api, repository, System.getenv("GH_TOKEN")).published() }
        .onFailure { warn("Couldn't read the earlier releases, so the notes list this one's changes only: $it") }
        .getOrDefault(emptyList())
        .filter { Versions.NEWEST_FIRST.compare(it.version, version) > 0 }
        .map { ReleaseChanges(it.version, PlayNotes.changes(it.changelog, scopes)) }
}

/** The notes of [releases] in each of Play's store listing languages that the app speaks and Claude translates. */
private fun translated(
    releases: List<ReleaseChanges>,
    about: String,
): Map<String, String> {
    val languages = PlayLanguages.ofApp(File(env("TRANSLATIONS") ?: "composeApp/src/commonMain/composeResources"))
    val lines = releases.flatMap { it.changes }.distinct()
    val translations =
        Translations.of(ClaudeTranslator(about), lines, languages.keys.toList()) { failed, reason ->
            warn("Google Play's notes stay in English for ${failed.joinToString(" ")}: $reason")
        }
    return translations
        .flatMap { (language, translation) ->
            val translate = lines.zip(translation).toMap()
            val notes = PlayNotes.compose(releases.map { it.copy(changes = it.changes.map(translate::getValue)) })
            languages.getValue(language).map { it to notes }
        }.toMap()
}

/** The environment variable [key], unless it is blank. */
private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

/** A warning, which GitHub Actions shows on the run's summary. */
private fun warn(message: String) = println("::warning::$message")
