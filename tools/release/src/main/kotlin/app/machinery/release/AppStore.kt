package app.machinery.release

import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Fills in the app's App Store Connect record from the listing its repository keeps (`store/app-store`, or the folder
 * given as the first argument; see [AppStoreListing]) and the screenshots its tests drew (one folder per display
 * type in `composeApp/build/store-art/app-store`, or the second argument). Run in the app's folder: the app's name
 * and bundle id come from its gradle.properties ([AppIdentity]). It never submits a version to App Review; the build
 * of TestFlight's external groups ([ExternalGroup]) goes to Beta App Review.
 *
 * `--check` first only reads and checks the listing and the screenshots, without App Store Connect.
 *
 * From the environment: `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY`, a team API key with the Admin role;
 * `RELEASE_VERSION`, the release whose build the version being prepared gets, waiting up to `BUILD_WAIT_MINUTES` for
 * App Store Connect to process it; `RELEASE_NOTES_DIR`, the release's notes as written for Google Play
 * (`whatsnew-<language>`), for TestFlight's What to Test; `GITHUB_STEP_SUMMARY`, where the report goes.
 */
fun main(args: Array<String>) {
    val check = args.firstOrNull() == "--check"
    val paths = if (check) args.drop(1) else args.toList()
    val dir = File(paths.getOrElse(0) { "store/app-store" })
    if (!dir.isDirectory) {
        println("No App Store listing in ${dir.path}: nothing to fill in.")
        return
    }
    val identity = AppIdentity.of()
    val listing = AppStoreListing.read(dir, identity.name)
    val art = File(paths.getOrElse(1) { "composeApp/build/store-art/app-store" })
    val screenshots = if (art.isDirectory) ScreenshotSync.from(art) else emptyMap()
    println(
        "The listing has ${listing.texts.size} languages and ${screenshots.values.sumOf { it.size }} screenshots " +
            "(${screenshots.keys.joinToString().ifEmpty { "none drawn" }}).",
    )
    if (check) return
    val report = AppStoreReport()
    val builds =
        BuildChoice(
            version = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() },
            wait = Duration.ofMinutes(System.getenv("BUILD_WAIT_MINUTES")?.toLongOrNull() ?: 0),
        )
    val notes =
        System
            .getenv("RELEASE_NOTES_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).listFiles { file -> file.isFile && file.name.startsWith(NOTES) }.orEmpty() }
            .orEmpty()
            .associate { it.name.removePrefix(NOTES) to it.readText(Charsets.UTF_8) }
    AppStoreSync(AppStoreConnect.fromEnvironment(), listing, screenshots, builds, report, notes).run(identity.id)
    report.writeSummary(System.getenv("GITHUB_STEP_SUMMARY"))
    if (report.failed) exitProcess(1)
}

/** How the release names its notes for Google Play, before the language ([main] of Main.kt). */
private const val NOTES = "whatsnew-"
