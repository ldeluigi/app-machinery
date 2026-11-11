package app.machinery.release

import java.io.File

/**
 * Brings the app's store listing on Google Play up to date, in one edit committed only if something changed:
 * - the default listing's graphics, with the ones StoreArtTest drew into `composeApp/build/store-art` (the first
 *   argument): the types that changed are replaced;
 * - every language's texts ([PlayListing]), from the App Store listing (`store/app-store`, the second argument) and
 *   the Google Play folder (`store/google-play`, the third): the languages whose texts changed are written, and the
 *   listing's other languages stay. An app with neither folder keeps its texts as they are.
 *
 * Run in the app's folder: the app's id and name come from its gradle.properties ([AppIdentity]). From the
 * environment: `PLAY_ACCESS_TOKEN`, an OAuth access token for the `androidpublisher` scope.
 */
fun main(args: Array<String>) {
    val app = AppIdentity.of()
    val art = StoreArt.from(File(args.getOrNull(0) ?: "composeApp/build/store-art"))
    val texts =
        PlayListing.read(
            appStore = File(args.getOrNull(1) ?: "store/app-store"),
            googlePlay = File(args.getOrNull(2) ?: "store/google-play"),
            appName = app.name,
        )
    val edit = PlayEdit.open(app.id, environment("PLAY_ACCESS_TOKEN"))
    val (images, languages) =
        runCatching { StoreArt.sync(art, edit) to PlayListing.sync(texts, edit) }
            // Play expires a forgotten edit by itself: the error that stopped the sync is the one to report.
            .onFailure { runCatching { edit.discard() } }
            .getOrThrow()
    if (images.isEmpty() && languages.isEmpty()) {
        edit.discard()
        println("The store listing is up to date.")
    } else {
        edit.commit()
        if (images.isNotEmpty()) println("Replaced the store listing's ${images.joinToString { it.apiName }}.")
        if (languages.isNotEmpty()) println("Wrote the store listing's texts in ${languages.joinToString()}.")
        println("Google reviews the listing again.")
    }
}

private fun environment(name: String): String =
    checkNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "$name is not set" }
