package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.io.IOException

class ClaudeTranslatorTest :
    FunSpec({
        test("the translations come from Claude Code's structured output") {
            val output =
                """
                {"type":"result","subtype":"success","is_error":false,"num_turns":2,
                 "result":"{\"translations\":{}}",
                 "structured_output":{"translations":{
                   "it":["Riproduci il brano dall'inizio","Componenti aggiornati."],
                   "de":["Song von Anfang an wiederholen",3]}}}
                """.trimIndent()
            ClaudeTranslator.parse(output) shouldBe
                mapOf(
                    "it" to listOf("Riproduci il brano dall'inizio", "Componenti aggiornati."),
                    "de" to listOf("Song von Anfang an wiederholen"),
                )
        }

        test("a failed run says why: expired token, usage limit, outage") {
            val output = """{"type":"result","is_error":true,"result":"Invalid bearer token"}"""
            shouldThrow<IOException> { ClaudeTranslator.parse(output) }.message shouldContain "Invalid bearer token"
        }

        test("an answer without translations fails") {
            shouldThrow<IOException> { ClaudeTranslator.parse("""{"is_error":false,"result":"Sorry"}""") }
        }

        test("without Claude Code there are no translations") {
            shouldThrow<IOException> {
                ClaudeTranslator("no-such-claude-command").translate(listOf("Fix the timer"), listOf("de"))
            }
        }

        test("Claude reads the lines and the languages, and must answer each language with as many lines") {
            ClaudeTranslator.request(listOf("Fix the timer"), listOf("de", "it")) shouldBe
                Json.parseToJsonElement("""{"lines":["Fix the timer"],"languages":["de","it"]}""")
            val translations =
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "de":{"type":"array","items":{"type":"string"},"minItems":2,"maxItems":2}},
                     "required":["de"],"additionalProperties":false}
                    """.trimIndent(),
                )
            ClaudeTranslator.schema(listOf("de"), 2) shouldBe
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{"translations":$translations},
                     "required":["translations"],"additionalProperties":false}
                    """.trimIndent(),
                )
        }
    })
