package app.machinery.release

import java.io.File

/**
 * Brings the graphics of the app's default store listing on Google Play up to date with the ones StoreArtTest drew
 * into `composeApp/build/store-art` (or the directory given as the first argument): replaces the types that changed,
 * all in one edit, and commits it only if something did. Run in the app's folder: the app's id comes from its
 * gradle.properties ([AppIdentity]). From the environment: `PLAY_ACCESS_TOKEN`, an OAuth access token for the
 * `androidpublisher` scope.
 */
fun main(args: Array<String>) {
    val art = StoreArt.from(File(args.firstOrNull() ?: "composeApp/build/store-art"))
    val edit = PlayEdit.open(AppIdentity.of().id, environment("PLAY_ACCESS_TOKEN"))
    val changed =
        runCatching { StoreArt.sync(art, edit) }
            // Play expires a forgotten edit by itself: the error that stopped the sync is the one to report.
            .onFailure { runCatching { edit.discard() } }
            .getOrThrow()
    if (changed.isEmpty()) {
        edit.discard()
        println("The store listing's graphics are up to date.")
    } else {
        edit.commit()
        println("Replaced the store listing's ${changed.joinToString { it.apiName }}: Google reviews them again.")
    }
}

private fun environment(name: String): String =
    checkNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "$name is not set" }
