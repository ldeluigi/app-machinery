import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks every translation of the Compose resources against the default language, as Android Lint does for Android
 * resources (MissingTranslation, ExtraTranslation, StringFormatMatches, MissingQuantity): Lint does not read Compose
 * resources, so without this a missing or broken translation would only show in the running app.
 *
 * Fails on a translatable text missing from a language, a text a language has but the default doesn't, an
 * untranslatable text that is translated, format arguments (`%1$s`, `%2$d`) that differ from the default's, a plural
 * without a form its language needs (by the Unicode plural rules, as Lint does), and a text array with a different
 * number of items.
 */
@CacheableTask
abstract class CheckTranslations : DefaultTask() {
    /** The Compose resources directory: `values/` is the default language, `values-<qualifiers>/` the others. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    /** The language of the untranslated `values` folder, as a BCP 47 tag such as `en-US`. */
    @get:Input
    abstract val defaultLocale: Property<String>

    /** Where the findings are written, one per line (empty when all is well). */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = resources.get().asFile
        val default = readTexts(root.resolve("values"))
        val problems =
            pluralForms("values", defaultLocale.get(), default) +
                root
                    .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
                    .orEmpty()
                    .sortedBy { it.name }
                    .flatMap { folder ->
                        val texts = readTexts(folder)
                        val tag = ResourceQualifiers.languageTag(folder.name.removePrefix("values-"))
                        compare(folder.name, default, texts) + (tag?.let { pluralForms(folder.name, it, texts) }.orEmpty())
                    }
        report.get().asFile.writeText(problems.joinToString("\n", postfix = if (problems.isEmpty()) "" else "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${problems.size} translation problem(s):\n" + problems.joinToString("\n") { "  $it" },
            )
        }
    }

    /** The plurals of [texts] that lack a form the plural rules of [languageTag] need. */
    private fun pluralForms(
        folder: String,
        languageTag: String,
        texts: Map<String, Text>,
    ): List<String> {
        val needed = PluralRules.forLocale(ULocale.forLanguageTag(languageTag)).keywords
        return texts.values.filter { it.kind == Kind.PLURALS }.mapNotNull { plural ->
            val missing = (needed - plural.forms.keys).sorted()
            missing.takeIf { it.isNotEmpty() }?.let { "$folder: '${plural.name}' lacks the form(s) $it" }
        }
    }

    private fun compare(
        language: String,
        default: Map<String, Text>,
        translated: Map<String, Text>,
    ): List<String> =
        buildList {
            default.values.filter { it.translatable && it.name !in translated }.forEach {
                add("$language: '${it.name}' is not translated")
            }
            translated.values.forEach { text ->
                val original = default[text.name]
                when {
                    original == null -> add("$language: '${text.name}' is not in the default language")
                    !original.translatable -> add("$language: '${text.name}' must not be translated")
                    original.kind != text.kind -> add("$language: '${text.name}' is a ${text.kind}, not a ${original.kind}")
                    else -> addAll(differences(language, original, text))
                }
            }
        }

    private fun differences(
        language: String,
        original: Text,
        text: Text,
    ): List<String> =
        buildList {
            text.forms.forEach { (form, value) ->
                val expected = original.arguments()
                val found = arguments(value)
                if (found != expected) {
                    add("$language: '${text.name}'${form.label()} has arguments $found instead of $expected")
                }
            }
            if (text.kind == Kind.ARRAY && text.forms.size != original.forms.size) {
                add("$language: '${text.name}' has ${text.forms.size} items instead of ${original.forms.size}")
            }
        }

    private fun String.label(): String = if (isEmpty()) "" else " ($this)"

    private enum class Kind { STRING, PLURALS, ARRAY }

    /** A text of the resources: one form for a string, one per quantity for a plural, one per item for an array. */
    private data class Text(
        val name: String,
        val kind: Kind,
        val translatable: Boolean,
        val forms: Map<String, String>,
    ) {
        /** The format arguments of the default language: those of every form together. */
        fun arguments(): Set<String> = forms.values.flatMap(::arguments).toSet()
    }

    private fun readTexts(folder: File): Map<String, Text> =
        folder
            .listFiles { file -> file.extension == "xml" }
            .orEmpty()
            .flatMap(::readFile)
            .associateBy { it.name }

    private fun readFile(file: File): List<Text> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.mapNotNull(::readText)
    }

    private fun readText(element: Element): Text? {
        val kind =
            when (element.tagName) {
                "string" -> Kind.STRING
                "plurals" -> Kind.PLURALS
                "string-array" -> Kind.ARRAY
                else -> return null
            }
        val items =
            (0 until element.childNodes.length)
                .mapNotNull { element.childNodes.item(it) as? Element }
                .filter { it.tagName == "item" }
        val forms =
            when (kind) {
                Kind.STRING -> mapOf("" to element.textContent)
                Kind.PLURALS -> items.associate { it.getAttribute("quantity") to it.textContent }
                Kind.ARRAY -> items.mapIndexed { index, item -> "item ${index + 1}" to item.textContent }.toMap()
            }
        return Text(element.getAttribute("name"), kind, element.getAttribute("translatable") != "false", forms)
    }

    private companion object {
        private val ARGUMENT = Regex("""%(\d+)\$[ds]""")

        fun arguments(text: String): Set<String> = ARGUMENT.findAll(text).map { it.value }.toSet()
    }
}
