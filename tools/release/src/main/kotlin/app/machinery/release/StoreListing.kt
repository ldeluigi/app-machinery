package app.machinery.release

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** The kinds of listing graphics an app draws, by the names the Google Play Developer API gives them. */
enum class ImageType(
    val apiName: String,
) {
    ICON("icon"),
    FEATURE_GRAPHIC("featureGraphic"),
    PHONE_SCREENSHOTS("phoneScreenshots"),
}

/** The images of one type in a store listing, as an edit of the app sees them. */
interface ListingImages {
    /** The SHA-256 of each image of [type], in the order Play shows them. */
    fun hashes(type: ImageType): List<String>

    /** Replaces every image of [type] with [files], in their order. */
    fun replace(
        type: ImageType,
        files: List<File>,
    )
}

/** Google Play's listing graphics as StoreArtTest draws them into `composeApp/build/store-art`. */
object StoreArt {
    /** The graphics in [dir] by type, the screenshots in the order of their names; fails if any type has none. */
    fun from(dir: File): Map<ImageType, List<File>> {
        val screenshots =
            dir
                .resolve("phone-screenshots")
                .listFiles { file -> file.extension == "png" }
                .orEmpty()
                .sortedBy { it.name }
        val art =
            mapOf(
                ImageType.ICON to listOf(dir.resolve("store-icon.png")).filter { it.isFile },
                ImageType.FEATURE_GRAPHIC to listOf(dir.resolve("feature-graphic.png")).filter { it.isFile },
                ImageType.PHONE_SCREENSHOTS to screenshots,
            )
        val missing = art.filterValues { it.isEmpty() }.keys
        check(missing.isEmpty()) {
            "No ${missing.joinToString { it.apiName }} in $dir: draw them first, with StoreArtTest"
        }
        return art
    }

    /**
     * Replaces the listing's images of each type whose [art] differs from them, in content or in order, and returns
     * those types. The others stay as they are: every change sends the listing to Google's review again.
     */
    fun sync(
        art: Map<ImageType, List<File>>,
        listing: ListingImages,
    ): List<ImageType> =
        art
            .filter { (type, files) -> listing.hashes(type).map { it.lowercase() } != files.map(::sha256) }
            .onEach { (type, files) -> listing.replace(type, files) }
            .keys
            .toList()

    /** The SHA-256 of [file] in lowercase hex, as Play lists it for an image. */
    fun sha256(file: File): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
}
