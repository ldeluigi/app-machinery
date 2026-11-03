package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory

/** A store listing in memory: the hashes Play would list, and the replacements made. */
private class FakeListing(
    val images: MutableMap<ImageType, List<String>>,
) : ListingImages {
    val replaced = mutableListOf<ImageType>()

    override fun hashes(type: ImageType): List<String> = images[type].orEmpty()

    override fun replace(
        type: ImageType,
        files: List<File>,
    ) {
        replaced += type
        images[type] = files.map(StoreArt::sha256)
    }
}

class StoreListingTest :
    FunSpec({
        val dir = createTempDirectory("store-art").toFile()
        afterSpec { dir.deleteRecursively() }

        fun write(
            name: String,
            content: String,
        ): File = dir.resolve(name).apply { parentFile.mkdirs() }.apply { writeText(content) }

        val icon = write("store-icon.png", "icon")
        val feature = write("feature-graphic.png", "feature")
        val podium = write("phone-screenshots/3-podium.png", "podium")
        val home = write("phone-screenshots/1-home.png", "home")
        val playing = write("phone-screenshots/2-playing.png", "playing")
        write("phone-screenshots/notes.txt", "not a screenshot")

        test("the graphics are found by type, the screenshots in the order of their names") {
            StoreArt.from(dir) shouldBe
                mapOf(
                    ImageType.ICON to listOf(icon),
                    ImageType.FEATURE_GRAPHIC to listOf(feature),
                    ImageType.PHONE_SCREENSHOTS to listOf(home, playing, podium),
                )
        }

        test("graphics that weren't drawn stop the upload") {
            val empty = createTempDirectory("no-art").toFile()
            try {
                shouldThrow<IllegalStateException> { StoreArt.from(empty) }.message shouldContain
                    "icon, featureGraphic, phoneScreenshots"
            } finally {
                empty.deleteRecursively()
            }
        }

        test("a listing showing the same images, whatever the case of their hashes, stays as it is") {
            val art = StoreArt.from(dir)
            val listing =
                FakeListing(
                    art.mapValuesTo(mutableMapOf()) { (_, files) -> files.map { StoreArt.sha256(it).uppercase() } },
                )
            StoreArt.sync(art, listing).shouldBeEmpty()
            listing.replaced.shouldBeEmpty()
        }

        test("only the types that changed are replaced") {
            val art = StoreArt.from(dir)
            val listing = FakeListing(art.mapValuesTo(mutableMapOf()) { (_, files) -> files.map(StoreArt::sha256) })
            listing.images[ImageType.PHONE_SCREENSHOTS] = listOf(home, playing).map(StoreArt::sha256)
            StoreArt.sync(art, listing) shouldContainExactly listOf(ImageType.PHONE_SCREENSHOTS)
            listing.replaced shouldContainExactly listOf(ImageType.PHONE_SCREENSHOTS)
        }

        test("the same screenshots in another order are replaced, and a listing without images gets them all") {
            val art = StoreArt.from(dir)
            val listing = FakeListing(mutableMapOf())
            listing.images[ImageType.PHONE_SCREENSHOTS] = listOf(playing, home, podium).map(StoreArt::sha256)
            StoreArt.sync(art, listing) shouldContainExactly ImageType.entries
        }

        test("Play's answers are read: the new edit's id and the hashes of the images listed") {
            PlayEdit.editId("""{"id": "0123", "expiryTimeSeconds": "1762000000"}""") shouldBe "0123"
            PlayEdit.imageHashes(
                """
                {"images": [
                  {"id": "a", "url": "https://play-lh.googleusercontent.com/a", "sha1": "1", "sha256": "ab12"},
                  {"id": "b", "url": "https://play-lh.googleusercontent.com/b", "sha1": "2", "sha256": "cd34"}
                ]}
                """.trimIndent(),
            ) shouldContainExactly listOf("ab12", "cd34")
            PlayEdit.imageHashes("{}").shouldBeEmpty()
        }
    })
