import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes Android's list of the app's languages (`res/xml/locales_config.xml`, shown in the phone's per-app language
 * setting from Android 13) from the translation folders. Android's own generator only sees Android resources of the
 * app module, while the translations are Compose resources of the shared library.
 */
@CacheableTask
abstract class GenerateLocaleConfig : DefaultTask() {
    /** Resource directories whose `values-<language>` folders hold translations. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: ConfigurableFileCollection

    /** The language of the untranslated `values` folder, as a BCP 47 tag such as `en-US`. */
    @get:Input
    abstract val defaultLocale: Property<String>

    /** The generated resource directory (`xml/locales_config.xml` inside). */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Writes `xml/locales_config.xml`: the default language, then the translations. */
    @TaskAction
    fun generate() {
        val translations =
            resources.files
                .flatMap { it.listFiles().orEmpty().toList() }
                .filter { it.isDirectory && it.resolve("strings.xml").isFile }
                .mapNotNull { ResourceQualifiers.languageTag(it.name.removePrefix("values").removePrefix("-")) }
        val tags = (listOf(defaultLocale.get()) + translations.sorted()).distinct()
        val file = outputDir.get().asFile.resolve("xml/locales_config.xml")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine("""<locale-config xmlns:android="http://schemas.android.com/apk/res/android">""")
                tags.forEach { appendLine("""    <locale android:name="$it" />""") }
                appendLine("</locale-config>")
            },
        )
    }
}
