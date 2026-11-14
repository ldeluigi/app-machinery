package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory

/** Writes [text] at [path] in this folder, making its folders. */
fun File.write(
    path: String,
    text: String,
): File = resolve(path).apply { parentFile.mkdirs() }.apply { writeText(text) }

/** A game in [dir]: two achievements, the second secret, in English, Italian and both Portuguese. */
fun gameIn(dir: File): File {
    val res = "composeApp/src/commonMain/composeResources"
    dir.write(
        "$res/values/strings.xml",
        """
        <resources>
            <string name="ach_first">Hello, world</string>
            <string name="ach_first_note">Play your first game</string>
            <string name="ach_blink">Speed reader</string>
            <string name="ach_blink_note">Don\'t read the \"question\"</string>
            <string name="other">Not an achievement</string>
        </resources>
        """.trimIndent(),
    )

    fun first(
        folder: String,
        name: String,
        note: String,
    ) = dir.write(
        "$res/$folder/strings.xml",
        """<resources><string name="ach_first">$name</string>""" +
            """<string name="ach_first_note">$note</string></resources>""",
    )
    first("values-it", "Ciao, mondo", "Gioca")
    first("values-pt", "Olá, mundo", "Jogue")
    first("values-pt-rPT", "Olá, mundo!", "Joga")
    return dir.write(
        Achievements.FILE,
        """
        # The game's achievements
        name = ach_{id}
        description = ach_{id}_note

        first 10
        blink 5 secret  # a joke
        """.trimIndent(),
    )
}

class AchievementsTest :
    FunSpec({
        val root = createTempDirectory("achievements").toFile()
        afterSpec { root.deleteRecursively() }

        test("a game's list reads its achievements with their texts in each of the app's languages") {
            val dir = root.resolve("game")
            val achievements = Achievements.read(gameIn(dir), "en-US", dir)
            achievements.list.map { it.id } shouldContainExactly listOf("first", "blink")
            achievements.list.map { it.secret } shouldContainExactly listOf(false, true)
            val first = achievements.list.first()
            first.points shouldBe 10
            first.texts.keys shouldBe setOf("en-US", "it", "pt", "pt-PT")
            first.texts.getValue("it") shouldBe AchievementText("Ciao, mondo", "Gioca")
            achievements.list
                .last()
                .texts
                .getValue("en-US")
                .description shouldBe "Don't read the \"question\""
            achievements.setting(Achievements.PICTURES) shouldBe "composeApp/build/store-art/achievements/game-center"
        }

        test("each store language takes the app language that covers it most closely") {
            val dir = root.resolve("closest")
            val (first, texts) = Achievements.read(gameIn(dir), "en-US", dir).localized(AppStoreLocales::codes).first()
            first.id shouldBe "first"
            texts.keys.toList() shouldContainExactly listOf("en-US", "it", "pt-BR", "pt-PT")
            texts.getValue("pt-PT").name shouldBe "Olá, mundo!"
            texts.getValue("pt-BR").name shouldBe "Olá, mundo"
            val play =
                Achievements
                    .read(gameIn(dir), "en-US", dir)
                    .localized(PlayLanguages::codes)
                    .first()
                    .second
            play.keys.toList() shouldContainExactly listOf("en-US", "it-IT", "pt-BR", "pt-PT")
        }

        test("a list the services can't take fails with every problem at once") {
            val dir = root.resolve("wrong")
            gameIn(dir)
            dir.write(
                Achievements.FILE,
                """
                name = ach_{id}
                first 10
                first 7
                Blink 100 secret
                missing 950
                odd one out
                """.trimIndent(),
            )
            val error =
                shouldThrow<IllegalStateException> { Achievements.read(dir.resolve(Achievements.FILE), "en-US", dir) }
            error.message shouldContain "Set description"
            error.message shouldContain "first has 7 points"
            error.message shouldContain "\"Blink\" isn't an id"
            error.message shouldContain "missing has 950 points"
            error.message shouldContain "\"odd one out\""
            error.message shouldContain "points: the services take 1000 at most"
            error.message shouldContain "Ids listed twice: first"
        }

        test("Android's escapes and quotes come off a resource string") {
            StringResources.unescape("Don\\'t \\\"stop\\\"\\nnow") shouldBe "Don't \"stop\"\nnow"
            StringResources.unescape("\"  spaced  \"") shouldBe "  spaced  "
        }

        test("the App Store's locales of the app's languages") {
            AppStoreLocales.codes("de") shouldContainExactly listOf("de-DE")
            AppStoreLocales.codes("en-US") shouldContainExactly listOf("en-US")
            AppStoreLocales.codes("it") shouldContainExactly listOf("it")
            AppStoreLocales.codes("pt") shouldContainExactly listOf("pt-BR", "pt-PT")
            AppStoreLocales.codes("nb") shouldContainExactly listOf("no")
            AppStoreLocales.codes("id") shouldContainExactly listOf("id")
            AppStoreLocales.codes("zh-TW") shouldContainExactly listOf("zh-Hant")
            AppStoreLocales.codes("fil") shouldBe emptyList()
        }

        test("the default language is the app's locale, English without one") {
            val dir = root.resolve("locale")
            defaultLanguage(dir.write("gradle.properties", "app.locale=it-IT")) shouldBe "it-IT"
            defaultLanguage(dir.resolve("none.properties")) shouldBe "en-US"
        }
    })
