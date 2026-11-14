package app.machinery.release

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/**
 * Brings a game's achievements up to date in a game service, from the list its repository keeps ([Achievements.FILE],
 * or the file given second) and the texts and pictures that list points to. The first argument names the service:
 * - `play-games`: Google Play Games, with `PLAY_ACCESS_TOKEN` (an OAuth access token for the `androidpublisher`
 *   scope, as for Google Play's listing). The ids Play makes for new achievements go into the app's ids file
 *   ([PlayGamesIds]) and into the run's summary, to be committed;
 * - `game-center`: Game Center, with `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (a team API key with the
 *   Admin role, as for the App Store listing);
 * - `check`: only reads and checks the list, its texts and, once drawn, its pictures.
 *
 * Run in the app's folder: the app's id and default language come from its gradle.properties. An app without the
 * list has no achievements, and nothing is done. The report goes to `GITHUB_STEP_SUMMARY`, when set.
 */
fun main(args: Array<String>) {
    val service = args.firstOrNull()
    require(service in SERVICES) { "Name the service first: ${SERVICES.joinToString()}" }
    val file = File(args.getOrElse(1) { Achievements.FILE })
    if (!file.isFile) {
        println("No ${file.path}: the app has no achievements.")
        return
    }
    val identity = AppIdentity.of()
    val achievements = Achievements.read(file, defaultLanguage(File("gradle.properties")))
    val pictures = File(achievements.setting(Achievements.PICTURES))
    val languages = achievements.list.flatMap { it.texts.keys }.toSet()
    val play =
        achievements
            .localized(PlayLanguages::codes)
            .firstOrNull()
            ?.second
            ?.size ?: 0
    val gameCenter =
        achievements
            .localized(AppStoreLocales::codes)
            .firstOrNull()
            ?.second
            ?.size ?: 0
    println(
        "${achievements.list.size} achievements of ${achievements.list.sumOf { it.points }} points in all, in " +
            "${languages.size} languages: $play of Google Play Games' and $gameCenter of Game Center's.",
    )
    val summary = System.getenv("GITHUB_STEP_SUMMARY")
    val report =
        when (service) {
            PLAY_GAMES -> {
                playGames(achievements, summary)
            }

            GAME_CENTER -> {
                gameCenter(achievements, pictures, identity.id)
            }

            else -> {
                if (pictures.isDirectory) {
                    val problems = GameCenterSync.pictureProblems(achievements, pictures)
                    check(problems.isEmpty()) { problems.joinToString("\n") }
                }
                return
            }
        }
    report.writeSummary(summary)
    if (report.failed) exitProcess(1)
}

private fun playGames(
    achievements: Achievements,
    summary: String?,
): AppStoreReport {
    val report = AppStoreReport("Google Play Games", OWNER_STEPS)
    val path = achievements.setting(Achievements.PLAY_GAMES_IDS)
    val ids = PlayGamesIds(File(path))
    report.step("Achievements") {
        val token =
            checkNotNull(System.getenv("PLAY_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }) {
                "PLAY_ACCESS_TOKEN is not set"
            }
        val written = PlayGamesSync(PlayGamesConfiguration(token), achievements, ids, report).run()
        if (written != null) {
            report.owner("Play made ids for new achievements: commit $path as the summary shows it")
            if (!summary.isNullOrBlank()) File(summary).appendText("\n### $path\n\n```xml\n$written```\n")
        }
    }
    return report
}

private fun gameCenter(
    achievements: Achievements,
    pictures: File,
    bundleId: String,
): AppStoreReport {
    val report = AppStoreReport("Game Center", OWNER_STEPS)
    report.step("Achievements") {
        val problems = GameCenterSync.pictureProblems(achievements, pictures)
        check(problems.isEmpty()) { "The achievements' pictures can't go up:\n" + problems.joinToString("\n") }
        GameCenterSync(AppStoreConnect.fromEnvironment(), achievements, pictures, report).run(bundleId)
    }
    return report
}

/** The language of the app's default texts: `app.locale` in its gradle.properties [file], English without it. */
fun defaultLanguage(file: File): String {
    if (!file.isFile) return ENGLISH
    val properties = Properties().apply { file.inputStream().use(::load) }
    return properties.getProperty("app.locale")?.takeIf { it.isNotBlank() } ?: ENGLISH
}

private const val PLAY_GAMES = "play-games"
private const val GAME_CENTER = "game-center"
private val SERVICES = listOf(PLAY_GAMES, GAME_CENTER, "check")
private const val ENGLISH = "en-US"
private const val OWNER_STEPS = "docs/02-new-app.md, step 12"
