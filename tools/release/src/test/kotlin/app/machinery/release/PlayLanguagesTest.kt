package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe

class PlayLanguagesTest :
    FunSpec({
        test("resource qualifiers that are only a language become BCP 47 tags, with today's codes") {
            listOf("de", "zh-rTW", "es-r419", "b+sr+Latn", "in", "iw").map(PlayLanguages::tag) shouldBe
                listOf("de", "zh-TW", "es-419", "sr-Latn", "id", "he")
        }

        test("other resource qualifiers aren't languages") {
            listOf("night", "v31", "de-land", "sw600dp", "zh-rTW-night").map(PlayLanguages::tag).toSet() shouldBe
                setOf(null)
        }

        test("a language covers its region's store listing, or else every listing of its language") {
            PlayLanguages.codes("de") shouldBe listOf("de-DE")
            PlayLanguages.codes("pt") shouldBe listOf("pt-BR", "pt-PT")
            PlayLanguages.codes("es") shouldBe listOf("es-419", "es-ES", "es-US")
            PlayLanguages.codes("zh-TW") shouldBe listOf("zh-TW")
            PlayLanguages.codes("ms") shouldBe listOf("ms", "ms-MY")
            PlayLanguages.codes("sr-Latn") shouldBe listOf("sr")
        }

        test("Play's old codes for Hebrew and Norwegian are found from today's") {
            PlayLanguages.codes("he") shouldBe listOf("iw-IL")
            PlayLanguages.codes("nb") shouldBe listOf("no-NO")
            PlayLanguages.codes("id") shouldBe listOf("id")
        }

        test("languages Play doesn't have cover no listing") {
            PlayLanguages.codes("yo").shouldBeEmpty()
            PlayLanguages.codes("pt-AO").shouldBeEmpty()
        }

        test("the app's languages are its translated strings, the ones Play has") {
            val res = tempdir()
            listOf("values", "values-de", "values-iw", "values-zh-rTW", "values-yo", "values-night").forEach {
                res.resolve(it).mkdirs()
                res.resolve("$it/strings.xml").writeText("<resources/>")
            }
            res.resolve("values-fr").mkdirs()
            PlayLanguages.ofApp(res) shouldBe
                mapOf("de" to listOf("de-DE"), "he" to listOf("iw-IL"), "zh-TW" to listOf("zh-TW"))
        }

        test("Play's codes are unique and include the default language") {
            PlayLanguages.CODES.shouldNotContainDuplicates()
            PlayLanguages.CODES shouldContain PlayLanguages.DEFAULT
        }
    })
