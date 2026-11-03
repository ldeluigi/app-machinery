package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.IOException
import java.util.Collections

class TranslationsTest :
    FunSpec({
        val lines = listOf("Replay the song from the beginning", "Fix the timer")

        /** Translates each line into "<language>: <line>". */
        val tagging = Translator { english, languages -> languages.associateWith { l -> english.map { "$l: $it" } } }

        test("every language is translated, a batch of languages per request") {
            val languages = (1..30).map { "l$it" }
            val batches = Collections.synchronizedList(mutableListOf<List<String>>())
            val translated =
                Translations.of(
                    { english, batch -> tagging.translate(english, batch).also { batches += batch } },
                    lines,
                    languages,
                ) { _, reason -> error(reason) }
            translated.keys shouldBe languages.toSet()
            translated.getValue("l7") shouldBe listOf("l7: Replay the song from the beginning", "l7: Fix the timer")
            batches.map { it.size } shouldContainExactlyInAnyOrder listOf(12, 12, 6)
        }

        test("a request that fails leaves its languages out, and says why") {
            val failures = mutableListOf<Pair<List<String>, String>>()
            val translated =
                Translations.of(
                    { _, _ -> throw IOException("Claude Code failed: Invalid bearer token") },
                    lines,
                    listOf("de", "it"),
                ) { languages, reason -> failures += languages to reason }
            translated.shouldBeEmpty()
            failures shouldBe listOf(listOf("de", "it") to "Claude Code failed: Invalid bearer token")
        }

        test("a language translated badly is left out, the others kept") {
            val failed = mutableListOf<String>()
            val translated =
                Translations.of(
                    { _, _ ->
                        mapOf(
                            "de" to listOf("  Das Lied von vorne abspielen ", "Timer repariert"),
                            "it" to listOf("Riproduci il brano dall'inizio"),
                        )
                    },
                    lines,
                    listOf("de", "it", "fr"),
                ) { languages, _ -> failed += languages }
            translated shouldBe mapOf("de" to listOf("Das Lied von vorne abspielen", "Timer repariert"))
            failed shouldBe listOf("it", "fr")
        }

        test("a translation is refused for missing, empty, broken, overlong or linking lines") {
            Translations.problem(lines, listOf("Das Lied von vorne abspielen", "Timer repariert")).shouldBeNull()
            listOf(
                null,
                listOf("Das Lied von vorne abspielen"),
                listOf("Das Lied von vorne abspielen", " "),
                listOf("Das Lied von vorne abspielen", "Timer\nrepariert"),
                listOf("Das Lied von vorne abspielen", "Timer repariert ".repeat(10)),
                listOf("Das Lied von vorne abspielen", "Mehr auf https://example.com"),
            ).forEach { Translations.problem(lines, it) shouldNotBe null }
        }
    })
