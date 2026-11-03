package app.machinery.release

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Translates Google Play's notes. */
fun interface Translator {
    /**
     * Each of the English [lines] in each of [languages] (BCP 47 tags), by language, in the order of [lines]. Throws
     * when it can't translate them.
     */
    fun translate(
        lines: List<String>,
        languages: List<String>,
    ): Map<String, List<String>>
}

/** The English notes' lines in other languages, as far as a [Translator] gets: the rest stays in English. */
object Translations {
    /** Languages per request: answers short enough to come back whole, and few requests at once. */
    const val BATCH = 12

    /** A translated line may be this many times as long as the English, plus [SLACK] characters. */
    private const val GROWTH = 4
    private const val SLACK = 40

    private val LINK = Regex("""://|www\.""", RegexOption.IGNORE_CASE)

    /**
     * [lines] in each of [languages] that [translator] translates well, by language: [BATCH] languages per request,
     * all requests at once. [onFailure] hears of each language left out, and why.
     */
    fun of(
        translator: Translator,
        lines: List<String>,
        languages: List<String>,
        onFailure: (languages: List<String>, reason: String) -> Unit,
    ): Map<String, List<String>> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val answers =
                languages.chunked(BATCH).associateWith { batch ->
                    executor.submit<Map<String, List<String>>> { translator.translate(lines, batch) }
                }
            answers
                .flatMap { (batch, answer) ->
                    runCatching { answer.get() }
                        .map { translated ->
                            batch.mapNotNull { language ->
                                val translation = translated[language]?.map(String::trim)
                                val problem = problem(lines, translation)
                                if (problem != null) onFailure(listOf(language), problem)
                                translation?.takeIf { problem == null }?.let { language to it }
                            }
                        }.getOrElse { error ->
                            val cause = (error as? ExecutionException)?.cause ?: error
                            onFailure(batch, cause.message ?: cause.toString())
                            emptyList()
                        }
                }.toMap()
        }

    /** What is wrong with [translation] as the translation of [english], if anything. */
    fun problem(
        english: List<String>,
        translation: List<String>?,
    ): String? {
        val pairs = english.zip(translation.orEmpty())
        return when {
            translation == null -> "no translation"
            translation.size != english.size -> "${translation.size} lines for ${english.size}"
            translation.any(String::isBlank) -> "an empty line"
            translation.any { it.lines().size > 1 } -> "a line break within a line"
            pairs.any { (en, it) -> it.length > GROWTH * en.length + SLACK } -> "a line far longer than the English"
            pairs.any { (en, it) -> LINK in it && LINK !in en } -> "a link the English lacks"
            else -> null
        }
    }
}
