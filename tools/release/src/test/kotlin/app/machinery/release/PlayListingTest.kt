package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory

/** Google Play's listing texts in memory, and what was written. */
private class FakeTexts(
    val listings: MutableMap<String, PlayTexts>,
) : ListingTexts {
    val written = mutableListOf<Pair<String, Boolean>>()

    override fun texts(): Map<String, PlayTexts> = listings.toMap()

    override fun write(
        language: String,
        texts: PlayTexts,
        new: Boolean,
    ) {
        written += language to new
        listings[language] = texts
    }
}

class PlayListingTest :
    FunSpec({
        val root = createTempDirectory("play-listing").toFile()
        afterSpec { root.deleteRecursively() }

        fun File.write(
            path: String,
            text: String,
        ): File = resolve(path).apply { parentFile.mkdirs() }.apply { writeText(text) }

        val description =
            """
            {app.name} is a party game.
            [app-store] Songs also play from Apple Music.
            [google-play] Songs play in the Spotify app.
            No ads.
            """.trimIndent()

        fun appStore(dir: File): File =
            dir.apply {
                write("app-store.properties", "primaryCategory=GAMES")
                write("review-notes.txt", "Notes")
                write("en-US/name.txt", "{app.name}")
                write("en-US/subtitle.txt", "A subtitle")
                write("en-US/promotional_text.txt", "Guess the year of every song")
                write("en-US/keywords.txt", "music,quiz")
                write("en-US/description.txt", description)
                write("ar-SA/name.txt", "{app.name}")
                write("ar-SA/subtitle.txt", "عنوان")
                write("ar-SA/description.txt", "وصف")
                write("zh-Hans/name.txt", "{app.name}")
                write("zh-Hans/promotional_text.txt", "猜年份")
                write("zh-Hans/description.txt", "描述")
            }

        test("the App Store's texts go to Play's languages, without the App Store's own lines") {
            val texts = PlayListing.read(appStore(root.resolve("a")), root.resolve("none"), "Example")

            texts.keys.toList() shouldContainExactly listOf("ar", "en-US", "zh-CN")
            texts.getValue("en-US") shouldBe
                PlayTexts(
                    "Example",
                    "Guess the year of every song",
                    "Example is a party game.\nSongs play in the Spotify app.\nNo ads.",
                )
            // No promotional text: the subtitle is the short description
            texts.getValue("ar").shortDescription shouldBe "عنوان"
        }

        test("the App Store keeps its own lines and leaves Google Play's out") {
            val listing = AppStoreListing.read(appStore(root.resolve("b")), "Example")

            listing.texts.getValue("en-US").getValue(ListingText.DESCRIPTION) shouldBe
                "Example is a party game.\nSongs also play from Apple Music.\nNo ads."
        }

        test("the Google Play folder adds languages and replaces texts, and a missing title is the app's name") {
            val play =
                root.resolve("c-play").apply {
                    write("bg/short_description.txt", "Кратко")
                    write("bg/full_description.txt", "Пълно описание")
                    write("en-US/short_description.txt", "Play's own short text")
                }

            val texts = PlayListing.read(appStore(root.resolve("c")), play, "Example")

            texts.getValue("bg") shouldBe PlayTexts("Example", "Кратко", "Пълно описание")
            texts.getValue("en-US").shortDescription shouldBe "Play's own short text"
            texts.getValue("en-US").title shouldBe "Example"
        }

        test("a text over Play's limits, or missing, stops the upload") {
            val play =
                root.resolve("d-play").apply {
                    write("de-DE/short_description.txt", "x".repeat(81))
                    write("de-DE/full_description.txt", "Beschreibung")
                    write("fr-FR/full_description.txt", "Description")
                }

            val error = shouldThrow<IllegalStateException> { PlayListing.read(root.resolve("none"), play, "Example") }

            error.message shouldContain "de-DE: short_description.txt has 81 characters, Play takes 80"
            error.message shouldContain "fr-FR has no short_description"
        }

        test("an app with neither folder has no texts") {
            PlayListing.read(root.resolve("none"), root.resolve("none"), "Example").shouldBeEmpty()
        }

        test("only the languages whose texts changed are written, a new one added, the others left alone") {
            val same = PlayTexts("Example", "Short", "Full")
            val listing =
                FakeTexts(
                    mutableMapOf(
                        "en-US" to same.copy(fullDescription = "Full\r\n"),
                        "de-DE" to PlayTexts("Example", "Alt", "Alt"),
                        "it-IT" to PlayTexts("Esempio", "Breve", "Lunga"),
                    ),
                )

            val changed =
                PlayListing.sync(
                    mapOf("en-US" to same, "de-DE" to PlayTexts("Example", "Neu", "Neu"), "bg" to same),
                    listing,
                )

            changed shouldContainExactly listOf("de-DE", "bg")
            listing.written shouldContainExactly listOf("de-DE" to false, "bg" to true)
            listing.listings.getValue("it-IT").title shouldBe "Esempio"
        }

        test("Play's listings are read from the API's answer") {
            val json =
                """{"listings":[{"language":"en-US","title":"Example","shortDescription":"Short",""" +
                    """"fullDescription":"Full","video":"https://youtu.be/x"},{"language":"de-DE","title":"Beispiel"}]}"""

            PlayEdit.listingTexts(json) shouldBe
                mapOf(
                    "en-US" to PlayTexts("Example", "Short", "Full"),
                    "de-DE" to PlayTexts("Beispiel", "", ""),
                )
            PlayEdit.listingTexts("{}").shouldBeEmpty()
        }

        test("App Store locales find their Google Play language") {
            mapOf(
                "en-US" to "en-US",
                "de-DE" to "de-DE",
                "ar-SA" to "ar",
                "ja" to "ja-JP",
                "he" to "iw-IL",
                "no" to "no-NO",
                "ms" to "ms",
                "pa-IN" to "pa",
                "es-MX" to "es-419",
                "zh-Hans" to "zh-CN",
                "zh-Hant" to "zh-TW",
                "screenshots" to null,
            ).forEach { (locale, play) -> PlayLanguages.ofAppStore(locale) shouldBe play }
        }
    })
