package app.machinery.release

import java.io.File

/**
 * What a run of the App Store tool (or of the achievements tool) did, what it left for the owner, and what failed,
 * under its [title], the owner's part pointing to [ownerSteps]. It names fields and states, never the people in the
 * team nor any key.
 */
class AppStoreReport(
    private val title: String = "App Store",
    private val ownerSteps: String = "docs/02-new-app.md, step 10b",
) {
    private val done = mutableListOf<String>()
    private val owner = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val facts = mutableListOf<String>()

    /** Whether a step failed. */
    val failed: Boolean get() = failures.isNotEmpty()

    /** Records something the tool changed in App Store Connect. */
    fun done(what: String) {
        done += what
        println("Done: $what")
    }

    /** Records something App Store Connect still needs that only the owner can give it. */
    fun owner(what: String) {
        owner += what
        println("For the owner: $what")
    }

    /** Records how things stand in App Store Connect. */
    fun fact(what: String) {
        facts += what
        println(what)
    }

    /** Runs the step [name], recording its failure instead of stopping the others. */
    fun step(
        name: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            val message = "$name: ${error.message ?: error::class.simpleName}"
            failures += message
            println("::error::${message.replace("\n", "%0A")}")
        }
    }

    /** The report in Markdown, for the run's summary. */
    fun markdown(): String =
        buildString {
            appendLine("## $title")
            section("How it stands", facts)
            section("Filled in by this run", done.ifEmpty { listOf("Nothing: the listing was already up to date.") })
            section("Left for the owner ($ownerSteps)", owner)
            section("Failed", failures)
        }

    /** Adds the report to the run's summary, when the run has one. */
    fun writeSummary(path: String?) {
        if (path.isNullOrBlank()) return
        File(path).appendText(markdown())
    }

    private fun StringBuilder.section(
        title: String,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("### $title")
        appendLine()
        lines.forEach { appendLine("- ${it.replace("\n", " ")}") }
    }
}
