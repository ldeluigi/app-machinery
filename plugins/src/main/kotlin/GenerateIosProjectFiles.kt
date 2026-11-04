import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes what the iOS app's Xcode project takes from the build, so that it is written in one place for both
 * platforms: its name, bundle id, team and version (`Generated.xcconfig`), and its `Info.plist`, filled in from a
 * template with the languages of the shared Compose resources.
 */
@CacheableTask
abstract class GenerateIosProjectFiles : DefaultTask() {
    /** The name the players see. */
    @get:Input
    abstract val appName: Property<String>

    /** The bundle id, the Android application id too. */
    @get:Input
    abstract val appId: Property<String>

    /** The version the App Store shows: `major.minor.patch`. */
    @get:Input
    abstract val marketingVersion: Property<String>

    /** The build number, always increasing: the Android version code. */
    @get:Input
    abstract val buildNumber: Property<Int>

    /** The Apple developer team that signs the app; empty for simulator builds, which aren't signed. */
    @get:Input
    abstract val teamId: Property<String>

    /** The language of the untranslated texts, as a BCP 47 tag such as `en-US`. */
    @get:Input
    abstract val defaultLocale: Property<String>

    /** The Compose resources, whose `values-<language>` folders are the translations. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    /** The Info.plist with a `<!-- LANGUAGES -->` line where the languages go. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val plistTemplate: RegularFileProperty

    /** The build settings the Xcode project reads. */
    @get:OutputFile
    abstract val xcconfig: RegularFileProperty

    /** The app's Info.plist. */
    @get:OutputFile
    abstract val plist: RegularFileProperty

    /** Writes `Generated.xcconfig` and the `Info.plist`. */
    @TaskAction
    fun generate() {
        xcconfig.get().asFile.apply { parentFile.mkdirs() }.writeText(
            buildString {
                appendLine(
                    "// Written by `./gradlew :composeApp:iosProjectFiles` from gradle.properties and git: don't edit.",
                )
                appendLine("APP_NAME = ${appName.get()}")
                appendLine("APP_ID = ${appId.get()}")
                appendLine("MARKETING_VERSION = ${marketingVersion.get()}")
                appendLine("CURRENT_PROJECT_VERSION = ${buildNumber.get()}")
                appendLine("DEVELOPMENT_TEAM = ${teamId.get()}")
            },
        )
        val languages =
            (listOf(defaultLocale.get()) + translations())
                .map(::appleLanguage)
                .distinct()
                .joinToString("\n") { "\t\t<string>$it</string>" }
        val template = plistTemplate.get().asFile.readText()
        check(MARKER in template) { "The Info.plist template has no $MARKER line" }
        plist
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(template.replace(MARKER, languages))
    }

    private fun translations(): List<String> =
        resources
            .get()
            .asFile
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.resolve("strings.xml").isFile }
            .mapNotNull { ResourceQualifiers.languageTag(it.name.removePrefix("values").removePrefix("-")) }
            .sorted()

    /** iOS names Chinese by its script and the other languages without their region: `zh-Hans`, `en`. */
    private fun appleLanguage(tag: String): String =
        when (tag) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> if (tag.startsWith("zh-")) tag else tag.substringBefore('-')
        }

    private companion object {
        private const val MARKER = "<!-- LANGUAGES -->"
    }
}
